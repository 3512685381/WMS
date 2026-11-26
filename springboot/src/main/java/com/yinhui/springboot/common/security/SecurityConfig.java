package com.yinhui.springboot.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yinhui.springboot.system.service.AuthService;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
@EnableMethodSecurity
@EnableScheduling
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService authService, ObjectMapper mapper)
            throws Exception {
        var login = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login");
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                SecurityErrorWriter.write(response, mapper, HttpStatus.UNAUTHORIZED, "请先登录"))
                        .accessDeniedHandler((request, response, error) ->
                                SecurityErrorWriter.write(response, mapper, HttpStatus.FORBIDDEN, "权限不足")))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(login).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthFilter(authService, mapper, login), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
