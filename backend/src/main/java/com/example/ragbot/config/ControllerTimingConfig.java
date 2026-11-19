package com.example.ragbot.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 统一记录 controller 层接口耗时，包含 SSE/异步请求的完整流转时间。 */
@Configuration
public class ControllerTimingConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TimingInterceptor()).addPathPatterns("/**");
    }

    private static class TimingInterceptor implements AsyncHandlerInterceptor {

        private static final Logger log = LoggerFactory.getLogger(TimingInterceptor.class);
        private static final String START_NS = TimingInterceptor.class.getName() + ".START_NS";
        private static final String HANDLER_LABEL = TimingInterceptor.class.getName() + ".HANDLER_LABEL";

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            // 只在初始 REQUEST 分发时计时；ASYNC/ERROR 分发复用同一次请求的起始时间。
            if (request.getDispatcherType() != DispatcherType.REQUEST || !isController(handler)) {
                return true;
            }
            request.setAttribute(START_NS, System.nanoTime());
            request.setAttribute(HANDLER_LABEL, describe(handler));
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                    Object handler, Exception ex) {
            Object start = request.getAttribute(START_NS);
            if (!(start instanceof Long startNs)) {
                return;
            }
            // 防止 ASYNC/ERROR 分发重复打印。
            request.removeAttribute(START_NS);
            Object label = request.getAttribute(HANDLER_LABEL);
            request.removeAttribute(HANDLER_LABEL);

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            String method = request.getMethod();
            String uri = request.getRequestURI();
            String controller = label == null ? describe(handler) : label.toString();
            int status = response.getStatus();

            if (ex != null || status >= 500) {
                log.warn("Controller耗时 {}ms | {} | {} {} | status={} | exception={}",
                        elapsedMs, controller, method, uri, status,
                        ex == null ? "-" : ex.getClass().getSimpleName());
            } else {
                log.info("Controller耗时 {}ms | {} | {} {} | status={}",
                        elapsedMs, controller, method, uri, status);
            }
        }

        private static boolean isController(Object handler) {
            if (!(handler instanceof HandlerMethod hm)) {
                return false;
            }
            Class<?> type = ClassUtils.getUserClass(hm.getBeanType());
            return type.getName().startsWith("com.example.ragbot.controller.")
                    && AnnotationUtils.findAnnotation(type, RestController.class) != null;
        }

        private static String describe(Object handler) {
            if (handler instanceof HandlerMethod hm) {
                Class<?> type = ClassUtils.getUserClass(hm.getBeanType());
                return type.getSimpleName() + "#" + hm.getMethod().getName();
            }
            return handler == null ? "unknown" : handler.getClass().getSimpleName();
        }
    }
}
