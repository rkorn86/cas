package org.apereo.cas.mgmt.config;

import org.apereo.cas.authentication.principal.ServiceFactory;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.model.support.oidc.OidcProperties;
import org.apereo.cas.configuration.model.webapp.mgmt.ManagementWebappProperties;
import org.apereo.cas.configuration.support.Beans;
import org.apereo.cas.mgmt.DefaultCasManagementEventListener;
import org.apereo.cas.mgmt.authz.CasRoleBasedAuthorizer;
import org.apereo.cas.mgmt.services.web.ManageRegisteredServicesMultiActionController;
import org.apereo.cas.mgmt.services.web.RegisteredServiceSimpleFormController;
import org.apereo.cas.mgmt.services.web.factory.AttributeFormDataPopulator;
import org.apereo.cas.mgmt.services.web.factory.DefaultRegisteredServiceFactory;
import org.apereo.cas.mgmt.services.web.factory.FormDataPopulator;
import org.apereo.cas.mgmt.services.web.factory.RegisteredServiceFactory;
import org.apereo.cas.mgmt.web.CasManagementRootController;
import org.apereo.cas.mgmt.web.CasManagementSecurityInterceptor;
import org.apereo.cas.oidc.claims.BaseOidcScopeAttributeReleasePolicy;
import org.apereo.cas.oidc.claims.OidcCustomScopeAttributeReleasePolicy;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.util.ResourceUtils;
import org.apereo.services.persondir.IPersonAttributeDao;
import org.pac4j.cas.client.direct.DirectCasClient;
import org.pac4j.cas.config.CasConfiguration;
import org.pac4j.core.authorization.authorizer.Authorizer;
import org.pac4j.core.authorization.generator.AuthorizationGenerator;
import org.pac4j.core.authorization.generator.FromAttributesAuthorizationGenerator;
import org.pac4j.core.authorization.generator.SpringSecurityPropertiesAuthorizationGenerator;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.direct.AnonymousClient;
import org.pac4j.core.config.Config;
import org.pac4j.http.client.direct.IpClient;
import org.pac4j.http.credentials.authenticator.IpRegexpAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.servlet.mvc.Controller;
import org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter;
import org.springframework.web.servlet.mvc.UrlFilenameViewController;
import org.thymeleaf.spring4.templateresolver.SpringResourceTemplateResolver;

import javax.servlet.Filter;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * This is {@link CasManagementWebAppConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
@Configuration("casManagementWebAppConfiguration")
@EnableConfigurationProperties(CasConfigurationProperties.class)
public class CasManagementWebAppConfiguration extends WebMvcConfigurerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(CasManagementWebAppConfiguration.class);

    @Autowired(required = false)
    @Qualifier("formDataPopulators")
    private List formDataPopulators = new ArrayList<>();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ServerProperties serverProperties;

    @Autowired
    private CasConfigurationProperties casProperties;

    @Autowired
    @Qualifier("webApplicationServiceFactory")
    private ServiceFactory<WebApplicationService> webApplicationServiceFactory;
    
    @Bean
    public Filter characterEncodingFilter() {
        return new CharacterEncodingFilter(StandardCharsets.UTF_8.name(), true);
    }

    @ConditionalOnMissingBean(name = "requireAnyRoleAuthorizer")
    @Bean
    @RefreshScope
    public Authorizer requireAnyRoleAuthorizer() {
        return new CasRoleBasedAuthorizer(casProperties.getMgmt().getAdminRoles());
    }

    @RefreshScope
    @ConditionalOnMissingBean(name = "attributeRepository")
    @Bean
    public IPersonAttributeDao attributeRepository() {
        return Beans.newStubAttributeRepository(casProperties.getAuthn().getAttributeRepository());
    }

    @ConditionalOnMissingBean(name = "authenticationClients")
    @Bean
    public List<Client> authenticationClients() {
        final List<Client> clients = new ArrayList<>();

        if (StringUtils.hasText(casProperties.getServer().getName())) {
            LOGGER.info("Configuring an authentication strategy based on CAS running at [{}]", casProperties.getServer().getName());
            final CasConfiguration cfg = new CasConfiguration(casProperties.getServer().getLoginUrl());
            final DirectCasClient client = new DirectCasClient(cfg);
            client.setAuthorizationGenerator(authorizationGenerator());
            client.setName("CasClient");
            clients.add(client);
        }

        if (StringUtils.hasText(casProperties.getMgmt().getAuthzIpRegex())) {
            LOGGER.info("Configuring an authentication strategy based on authorized IP addresses matching [{}]", casProperties.getMgmt().getAuthzIpRegex());
            final IpClient ipClient = new IpClient(new IpRegexpAuthenticator(casProperties.getMgmt().getAuthzIpRegex()));
            ipClient.setName("IpClient");
            ipClient.setAuthorizationGenerator(getStaticAdminRolesAuthorizationGenerator());
            clients.add(ipClient);
        }

        if (clients.isEmpty()) {
            LOGGER.warn("No authentication strategy is defined, CAS will establish an anonymous authentication mode whereby access is immediately granted. "
                    + "This may NOT be relevant for production purposes. Consider configuring alternative authentication strategies for maximum security.");
            final AnonymousClient anon = AnonymousClient.INSTANCE;
            anon.setAuthorizationGenerator(getStaticAdminRolesAuthorizationGenerator());
            clients.add(anon);
        }
        return clients;
    }

    @Bean
    public Config config() {
        final Config cfg = new Config(getDefaultServiceUrl(), authenticationClients());
        cfg.setAuthorizer(requireAnyRoleAuthorizer());
        return cfg;
    }

    @Bean
    public Controller rootController() {
        return new CasManagementRootController();
    }

    @Bean
    public SimpleUrlHandlerMapping handlerMappingC() {
        final SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(1);
        mapping.setAlwaysUseFullPath(true);
        mapping.setRootHandler(rootController());

        final Properties properties = new Properties();
        properties.put("/*.html", new UrlFilenameViewController());
        mapping.setMappings(properties);
        return mapping;
    }

    @Bean
    public HandlerInterceptorAdapter casManagementSecurityInterceptor() {
        return new CasManagementSecurityInterceptor(config());
    }

    @RefreshScope
    @Bean
    public Properties userProperties() {
        try {
            final Properties p = new Properties();

            final ManagementWebappProperties mgmt = casProperties.getMgmt();
            if (ResourceUtils.doesResourceExist(mgmt.getUserPropertiesFile())) {
                p.load(mgmt.getUserPropertiesFile().getInputStream());
            } else {
                LOGGER.warn("Could not locate [{}]", mgmt.getUserPropertiesFile());
            }

            return p;
        } catch (final Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @ConditionalOnMissingBean(name = "authorizationGenerator")
    @Bean
    @RefreshScope
    public AuthorizationGenerator authorizationGenerator() {
        final List<String> authzAttributes = casProperties.getMgmt().getAuthzAttributes();
        if (!authzAttributes.isEmpty()) {
            if (authzAttributes.stream().anyMatch(a -> a.equals("*"))) {
                return getStaticAdminRolesAuthorizationGenerator();
            }
            return new FromAttributesAuthorizationGenerator(authzAttributes.toArray(new String[]{}), new String[]{});
        }
        return new SpringSecurityPropertiesAuthorizationGenerator(userProperties());
    }

    private AuthorizationGenerator getStaticAdminRolesAuthorizationGenerator() {
        return (context, profile) -> {
            profile.addRoles(casProperties.getMgmt().getAdminRoles());
            return profile;
        };
    }

    @ConditionalOnMissingBean(name = "localeResolver")
    @Bean
    public LocaleResolver localeResolver() {
        return new CookieLocaleResolver() {
            @Override
            protected Locale determineDefaultLocale(final HttpServletRequest request) {
                final Locale locale = request.getLocale();
                if (StringUtils.isEmpty(casProperties.getMgmt().getDefaultLocale())
                        || !locale.getLanguage().equals(casProperties.getMgmt().getDefaultLocale())) {
                    return locale;
                }
                return new Locale(casProperties.getMgmt().getDefaultLocale());
            }
        };
    }

    @RefreshScope
    @Bean
    public HandlerInterceptor localeChangeInterceptor() {
        final LocaleChangeInterceptor bean = new LocaleChangeInterceptor();
        bean.setParamName(this.casProperties.getLocale().getParamName());
        return bean;
    }

    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
        registry.addInterceptor(casManagementSecurityInterceptor())
                .addPathPatterns("/**").excludePathPatterns("/callback*", "/logout*", "/authorizationFailure");
    }

    @Bean
    public SimpleControllerHandlerAdapter simpleControllerHandlerAdapter() {
        return new SimpleControllerHandlerAdapter();
    }

    @Bean
    public RegisteredServiceFactory registeredServiceFactory() {
        this.formDataPopulators.add(attributeFormDataPopulator());
        return new DefaultRegisteredServiceFactory(formDataPopulators);
    }

    @Bean
    public FormDataPopulator attributeFormDataPopulator() {
        return new AttributeFormDataPopulator(attributeRepository());
    }

    @Bean
    public ManageRegisteredServicesMultiActionController manageRegisteredServicesMultiActionController(
            @Qualifier("servicesManager") final ServicesManager servicesManager) {
        return new ManageRegisteredServicesMultiActionController(servicesManager, registeredServiceFactory(),
                webApplicationServiceFactory, getDefaultServiceUrl(), casProperties);
    }

    @Bean
    public RegisteredServiceSimpleFormController registeredServiceSimpleFormController(@Qualifier("servicesManager") final ServicesManager servicesManager) {
        return new RegisteredServiceSimpleFormController(servicesManager, registeredServiceFactory());
    }

    private String getDefaultServiceUrl() {
        return casProperties.getMgmt().getServerName().concat(serverProperties.getContextPath()).concat("/manage.html");
    }

    @RefreshScope
    @Bean
    public Collection<BaseOidcScopeAttributeReleasePolicy> userDefinedScopeBasedAttributeReleasePolicies() {
        final OidcProperties oidc = casProperties.getAuthn().getOidc();
        return oidc.getUserDefinedScopes().entrySet()
                .stream()
                .map(k -> new OidcCustomScopeAttributeReleasePolicy(k.getKey(), CollectionUtils.wrapList(k.getValue().split(","))))
                .collect(Collectors.toSet());
    }

    @Bean
    public DefaultCasManagementEventListener defaultCasManagementEventListener() {
        return new DefaultCasManagementEventListener();
    }

    @Bean
    public SpringResourceTemplateResolver staticTemplateResolver() {
        final SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(this.context);
        resolver.setPrefix("classpath:/dist/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(Charset.forName("UTF-8").name());
        resolver.setCacheable(false);
        resolver.setOrder(0);
        resolver.setCheckExistence(true);
        return resolver;
    }

    @Override
    public void addResourceHandlers(final ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**").addResourceLocations("classpath:/dist/", "classpath:/static/");
    }
}
