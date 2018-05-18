package com.kakawait;

import com.kakawait.spring.boot.security.cas.CasHttpSecurityConfigurer;
import com.kakawait.spring.boot.security.cas.CasSecurityConfigurerAdapter;
import com.kakawait.spring.security.cas.client.CasAuthorizationInterceptor;
import com.kakawait.spring.security.cas.client.ticket.ProxyTicketProvider;
import com.kakawait.spring.security.cas.client.validation.AssertionProvider;
import org.jasig.cas.client.authentication.AttributePrincipal;
import org.jasig.cas.client.authentication.AttributePrincipalImpl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.Http401AuthenticationEntryPoint;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.security.cas.authentication.CasAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

import java.lang.reflect.Field;
import java.security.Principal;
import java.util.Optional;

/**
 * @author Thibaud Leprêtre
 */
@SpringBootApplication
@EnableGlobalMethodSecurity(securedEnabled = true)
public class CasSecuritySpringBootSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(CasSecuritySpringBootSampleApplication.class, args);
    }

    @Bean
    FilterRegistrationBean forwardedHeaderFilter() {
        FilterRegistrationBean filterRegBean = new FilterRegistrationBean();
        filterRegBean.setFilter(new ForwardedHeaderFilter());
        filterRegBean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return filterRegBean;
    }

    @Bean
    RestTemplate casRestTemplate(ServiceProperties serviceProperties, ProxyTicketProvider proxyTicketProvider) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new CasAuthorizationInterceptor(serviceProperties, proxyTicketProvider));
        return restTemplate;
    }

    @Profile("!custom-logout")
    @Configuration
    static class LogoutConfiguration extends CasSecurityConfigurerAdapter {

        private final LogoutSuccessHandler casLogoutSuccessHandler;

        public LogoutConfiguration(LogoutSuccessHandler casLogoutSuccessHandler) {
            this.casLogoutSuccessHandler = casLogoutSuccessHandler;
        }

        @Override
        public void configure(HttpSecurity http) throws Exception {
            // Allow GET method to /logout even if CSRF is enabled
            http.logout()
                .logoutSuccessHandler(casLogoutSuccessHandler)
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout"));
        }
    }

    @Configuration
    static class ApiSecurityConfiguration extends WebSecurityConfigurerAdapter {
        @Override
        protected void configure(HttpSecurity http) throws Exception {
            http.antMatcher("/api/**").authorizeRequests().anyRequest().authenticated();
            // Applying CAS security on current HttpSecurity (FilterChain)
            // I'm not using .apply() from HttpSecurity due to following issue
            // https://github.com/spring-projects/spring-security/issues/4422
            CasHttpSecurityConfigurer.cas().configure(http);
            http.exceptionHandling().authenticationEntryPoint(new Http401AuthenticationEntryPoint("CAS"));
        }
    }

    @Profile("custom-logout")
    @Configuration
    static class CustomLogoutConfiguration extends CasSecurityConfigurerAdapter {

        private final LogoutSuccessHandler casLogoutSuccessHandler;

        public CustomLogoutConfiguration(LogoutSuccessHandler casLogoutSuccessHandler) {
            this.casLogoutSuccessHandler = casLogoutSuccessHandler;
        }

        @Override
        public void configure(HttpSecurity http) throws Exception {
            http.logout()
                .permitAll()
                // Add null logoutSuccessHandler to disable CasLogoutSuccessHandler
                .logoutSuccessHandler(null)
                .logoutSuccessUrl("/logout.html")
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout"));
            LogoutFilter filter = new LogoutFilter(casLogoutSuccessHandler, new SecurityContextLogoutHandler());
            filter.setFilterProcessesUrl("/cas/logout");
            http.addFilterBefore(filter, LogoutFilter.class);
        }
    }

    @Profile("custom-logout")
    @Configuration
    static class WebMvcConfiguration extends WebMvcConfigurerAdapter {
        @Override
        public void addViewControllers(ViewControllerRegistry registry) {
            registry.addViewController("/logout.html").setViewName("logout");
            registry.setOrder(Ordered.HIGHEST_PRECEDENCE);
        }
    }

    @Controller
    @RequestMapping(value = "/")
    static class IndexController {

        private final RestTemplate casRestTemplate;

        private final ProxyTicketProvider proxyTicketProvider;

        private final AssertionProvider assertionProvider;

        public IndexController(RestTemplate casRestTemplate, ProxyTicketProvider proxyTicketProvider,
                AssertionProvider assertionProvider) {
            this.casRestTemplate = casRestTemplate;
            this.proxyTicketProvider = proxyTicketProvider;
            this.assertionProvider = assertionProvider;
        }

        @RequestMapping
        public String hello(Authentication authentication, Model model) {
            if (authentication != null && StringUtils.hasText(authentication.getName())) {
                model.addAttribute("username", authentication.getName());
                model.addAttribute("principal", authentication.getPrincipal());
                model.addAttribute("pgt", getProxyGrantingTicket(authentication).orElse(null));
            }
            return "index";
        }

        @RequestMapping("/proxy-ticket")
        public @ResponseBody String ticket(@RequestParam(value = "service") String service,
                Authentication authentication, Principal principal) {
            String template = "Get proxy ticket using %s for service %s = %s";
            // Simplest (except directly using RestTemplate see method just below)
            String s1 = String.format(template, "ProxyTicketProvider", service,
                    proxyTicketProvider.getProxyTicket(service));
            // Simple
            String s2 = String.format(template, "AssertionProvider", service,
                    assertionProvider.getAssertion().getPrincipal().getProxyTicketFor(service));
            // Old school
            String s3 = String.format(template, "Authentication object", service,
                    getAttributePrincipal(authentication).map(p -> p.getProxyTicketFor(service)).orElse(null));
            String s4 = String.format(template, "Principal object", service,
                    getAttributePrincipal(principal).map(p -> p.getProxyTicketFor(service)).orElse(null));
            return s1 + "<br/>" + s2 + "<br/>" + s3 + "<br/>" + s4;
        }

        @RequestMapping({"/httpbin", "/rest-template"})
        public @ResponseBody String httpbin() {
            return casRestTemplate.getForEntity("http://httpbin.org/get", String.class).getBody();
        }

        @RequestMapping(path = "/ignored")
        public String ignored() {
            return "index";
        }

        @Secured("ROLE_ADMIN")
        @RequestMapping(path = "/admin")
        public @ResponseBody String roleUsingAnnotation() {
            return "You're admin";
        }

        private Optional<AttributePrincipal> getAttributePrincipal(Object o) {
            if (!(o instanceof CasAuthenticationToken)) {
                return Optional.empty();
            }
            return Optional.of(((CasAuthenticationToken) o).getAssertion().getPrincipal());
        }

        /**
         * Hacky code please do not use that in production
         */
        private Optional<String> getProxyGrantingTicket(Authentication authentication) {
            Optional<AttributePrincipal> attributePrincipal = getAttributePrincipal(authentication);
            if (!attributePrincipal.isPresent() || !(attributePrincipal.get() instanceof AttributePrincipalImpl)) {
                return Optional.empty();
            }
            Field field = ReflectionUtils.findField(AttributePrincipalImpl.class, "proxyGrantingTicket");
            ReflectionUtils.makeAccessible(field);
            return Optional.ofNullable(ReflectionUtils.getField(field, attributePrincipal.get())).map(Object::toString);
        }
    }

    @RestController
    @RequestMapping(value = "/api")
    static class HelloWorldController {

        @RequestMapping
        public @ResponseBody String hello(Principal principal) {
            return principal == null ? "Hello anonymous" : "Hello " + principal.getName();
        }
    }

}
