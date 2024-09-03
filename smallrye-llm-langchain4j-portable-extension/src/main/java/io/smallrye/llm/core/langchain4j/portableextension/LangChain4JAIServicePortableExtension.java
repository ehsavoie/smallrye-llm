package io.smallrye.llm.core.langchain4j.portableextension;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessInjectionPoint;
import jakarta.enterprise.inject.spi.WithAnnotations;
import jakarta.enterprise.inject.spi.configurator.BeanConfigurator;

import org.jboss.logging.Logger;

import io.smallrye.llm.aiservice.CommonAIServiceCreator;
import io.smallrye.llm.spi.RegisterAIService;

public class LangChain4JAIServicePortableExtension implements Extension {

    private static final Logger LOGGER = Logger.getLogger(LangChain4JAIServicePortableExtension.class);
    private static final Set<Class<?>> detectedAIServicesDeclaredInterfaces = new HashSet<>();
    private final Set<AnnotatedType<?>> annotatedTypes = new HashSet<>();
    private final Set<InjectionPoint> componentInjectionPoints = new HashSet<>();
    private final Set<InjectionPoint> instanceInjectionPoints = new HashSet<>();

    public static Set<Class<?>> getDetectedAIServicesDeclaredInterfaces() {
        return Collections.unmodifiableSet(detectedAIServicesDeclaredInterfaces);
    }

    <T> void processAnnotatedType(@Observes @WithAnnotations({ RegisterAIService.class }) ProcessAnnotatedType<T> pat) {
        if (pat.getAnnotatedType().getJavaClass().isInterface()) {
            LOGGER.info("processAnnotatedType register " + pat.getAnnotatedType().getJavaClass().getName());
            annotatedTypes.add(pat.getAnnotatedType());
        } else {
            LOGGER.warn("processAnnotatedType reject " + pat.getAnnotatedType().getJavaClass().getName()
                    + " which is not an interface");
            pat.veto();
        }
    }

    /**
     * This is useful for application servers that can't support proccessAnnotatedType.
     *
     * @param event
     */
    void processInjectionPoints(@Observes ProcessInjectionPoint<?, ?> event) {
        if (event.getInjectionPoint().getBean() == null) {
            componentInjectionPoints.add(event.getInjectionPoint());
        }

        if (Instance.class.equals(Reflections.getRawType(event.getInjectionPoint().getType()))) {
            instanceInjectionPoints.add(event.getInjectionPoint());
        }
    }

    void afterBeanDiscovery(@Observes AfterBeanDiscovery afterBeanDiscovery, BeanManager beanManager)
            throws ClassNotFoundException {
        for (AnnotatedType<?> annotatedType : annotatedTypes) {
            LOGGER.info("Adding @RegisterAIService of interface '" + annotatedType.getJavaClass().getName()
                    + "', discovered during processAnnotatedType(), for component injection.");
            RegisterAIService registerAiServiceAnnotation = annotatedType.getJavaClass().getAnnotation(RegisterAIService.class);
            addBean(afterBeanDiscovery, annotatedType.getJavaClass(), registerAiServiceAnnotation, false);
        }

        for (InjectionPoint ip : componentInjectionPoints) {
            Class<?> rawType = Reflections.getRawType(ip.getType());
            RegisterAIService registerAiServiceAnnotation = rawType.getAnnotation(RegisterAIService.class);
            addBean(afterBeanDiscovery, rawType, registerAiServiceAnnotation, false);
        }

        for (InjectionPoint ip : instanceInjectionPoints) {
            Class<?> rawType = Reflections.getRawType(ip.getType());
            RegisterAIService registerAiServiceAnnotation = rawType.getAnnotation(RegisterAIService.class);
            addBean(afterBeanDiscovery, rawType, registerAiServiceAnnotation, true);
        }
    }

    private void addBean(AfterBeanDiscovery abd, Class<?> interfaceClass, RegisterAIService registerAiServiceAnnotation,
            boolean produce) {
        if (interfaceClass.isInterface() && registerAiServiceAnnotation != null) {
            detectedAIServicesDeclaredInterfaces.add(interfaceClass);
            BeanConfigurator<Object> bc = abd.addBean()
                    .scope(registerAiServiceAnnotation.scope())
                    .types(interfaceClass)
                    .name("registeredAIService-" + interfaceClass.getName()); //Without this, the container won't create a CreationalContext
            if (produce) {
                bc.produceWith(c -> CommonAIServiceCreator.create(c, interfaceClass));
            } else {
                bc.createWith(c -> CommonAIServiceCreator.create(CDI.current(), interfaceClass));
            }
            LOGGER.info("Added @RegisterAIService of interface type '" + interfaceClass.getName() + "' for "
                    + (produce ? "instance" : "component") + " injection.");
        }
    }
}
