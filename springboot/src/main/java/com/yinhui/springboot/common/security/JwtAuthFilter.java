package com.yinhui.springboot.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yinhui.springboot.common.exception.ApiException;
import com.yinhui.springboot.system.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final RequestMatcher loginRequest;

    public JwtAuthFilter(AuthService authService, ObjectMapper objectMapper, RequestMatcher loginRequest) {
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.loginRequest = loginRequest;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return loginRequest.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            SecurityErrorWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED, "请使用 Bearer 登录凭证");
            return;
        }

        CurrentUser currentUser;
        try {
            currentUser = authService.authenticate(authorization.substring(BEARER_PREFIX.length()).strip());
        } catch (ApiException error) {
            SecurityErrorWriter.write(response, objectMapper, error.getStatus(), error.getMessage());
            return;
        } catch (RuntimeException error) {
            log.error("读取登录权限失败", error);
            SecurityErrorWriter.write(response, objectMapper, HttpStatus.INTERNAL_SERVER_ERROR, "服务器处理请求失败");
            return;
        }

        var authorities = new ArrayList<SimpleGrantedAuthority>();
        for (String role : currentUser.user().roles()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        for (String permission : currentUser.user().permissions()) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        var authentication = UsernamePasswordAuthenticationToken.authenticated(currentUser, null, authorities);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
