package dev.deskflow;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Configuration
class Security {
    @Bean BCryptPasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll())
            .sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .httpBasic(b->b.disable()).formLogin(f->f.disable()).build();
    }
}

@Component
class SessionFilter extends OncePerRequestFilter {
    final JdbcTemplate db;
    SessionFilter(JdbcTemplate db) { this.db=db; }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String path=req.getRequestURI();
        if(path.startsWith("/api/")) {
            if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod()) && !"1".equals(req.getHeader("X-Deskflow-Request"))) {
                reject(res,403,"Missing same-origin request header"); return;
            }
            if(req.getContentLengthLong()>131072) { reject(res,413,"Request too large"); return; }
            if(req.getCookies()!=null) for(var cookie:req.getCookies()) if(cookie.getName().equals("deskflow_session")) {
                var ids=db.queryForList("select user_id from sessions where token_hash=? and expires_at>now()",UUID.class,Secrets.hash(cookie.getValue()));
                if(!ids.isEmpty()) req.setAttribute("userId",ids.getFirst());
            }
            if(!Set.of("/api/auth/login","/api/auth/register").contains(path) && req.getAttribute("userId")==null) {
                reject(res,401,"Please sign in"); return;
            }
        }
        chain.doFilter(req,res);
    }
    void reject(HttpServletResponse r,int status,String msg) throws IOException { r.setStatus(status); r.setContentType("application/json"); r.getWriter().write(Json.write(Map.of("error",msg))); }
}
