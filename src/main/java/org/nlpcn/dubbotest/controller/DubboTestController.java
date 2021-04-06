package org.nlpcn.dubbotest.controller;

import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nlpcn.dubbotest.Application;
import org.nlpcn.dubbotest.util.ArtifactUtils;
import org.nlpcn.dubbotest.util.DependencyUtils;
import org.nlpcn.dubbotest.util.PojoUtils;
import org.nlpcn.dubbotest.vm.ApiVM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import javax.validation.Valid;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * @author : qihang.liu
 * @date 2021-04-06
 */
@RestController
public class DubboTestController {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    private static final String APPLICATION_NAME = "api-generic-consumer";

    private static final int DEFAULT_TIMEOUT = 20 * 1000;

    @Value("${mavenLocalRepository:}")
    private String mavenLocalRepository;

    @Autowired
    private ObjectMapper mapper;

    @PostConstruct
    public void init() {
        if (StringUtils.isEmpty(mavenLocalRepository)) {
            mavenLocalRepository = System.getenv("HOME") + File.separator + ".m2" + File.separator + "repository";
        }

        LOG.info("Maven local repository is {}", mavenLocalRepository);
    }

    @PostMapping(value = "/dubbo/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object testDubboApi(@RequestBody @Valid ApiVM api, @RequestParam(value = "_force", defaultValue = "false") boolean force) throws Exception {
        if (!StringUtils.hasText(api.getName())) {
            api.setName(APPLICATION_NAME);
            LOG.info("use default application name: {}", APPLICATION_NAME);
        }
        if (!StringUtils.hasText(api.getService())) {
            String m = api.getMethod();
            int i = m.lastIndexOf("#");
            if (i < 0) {
                i = m.lastIndexOf(".");
            }
            api.setService(m.substring(0, i));
            api.setMethod(m.substring(i + 1));
            LOG.info("[service] not found, substing method[{}]：service[{}]，method[{}]", m, api.getService(), api.getMethod());
        }

        // 解析所有的依赖
        List<String> allJar = new ArrayList<>();
        String[] indexer = DependencyUtils.getMavenIndexer(api.getDependency());
        List<String> dependencyList = DependencyUtils.parseJarDependency(mavenLocalRepository, indexer[0], indexer[1], indexer[2]);
        allJar.add(api.getDependency());
        allJar.addAll(dependencyList);

        // 下载所有的jar包
        List<Path> allPath = new ArrayList<>();
        for (String jar : allJar) {
            String[] arr1 = com.alibaba.dubbo.common.utils.StringUtils.split(jar, ':');
            Path p = ArtifactUtils.download(force, mavenLocalRepository, arr1[0], arr1[1], arr1[2]);
            allPath.add(p);
        }

        // 查找到全部jar包
        List<URL> urls = new LinkedList<>();
        for (Path p1 : allPath) {
            Files.walkFileTree(p1, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
                    File f = p.toFile();
                    if (f.getName().toLowerCase().endsWith(".jar")) {
                        urls.add(f.toURI().toURL());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        // 加载jar包
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), contextClassLoader)) {
            Thread.currentThread().setContextClassLoader(cl);

            List<Object> args = Optional.ofNullable(api.getArgs()).orElse(Collections.emptyList());
            Method invokeMethod = findMethod(ClassHelper.forName(api.getService()), api.getMethod(), args);
            if (invokeMethod != null) {
                RegistryConfig registry = new RegistryConfig();
                if (StringUtils.hasText(api.getAddress())) {
                    registry.setAddress(api.getAddress());
                }
                ApplicationConfig application = new ApplicationConfig();
                application.setRegistry(registry);
                application.setName(api.getName());

                ReferenceConfig<GenericService> reference = new ReferenceConfig<>();
                reference.setApplication(application);
                reference.setInterface(api.getService());
                reference.setGeneric(true);
                if (StringUtils.hasText(api.getUrl())) {
                    reference.setUrl(api.getUrl());
                }
                reference.setTimeout(Optional.ofNullable(api.getTimeout()).orElse(DEFAULT_TIMEOUT));

                long start = System.currentTimeMillis();

                Object result = reference.get().$invoke(api.getMethod(), Arrays.stream(invokeMethod.getParameterTypes()).map(Class::getName).toArray(String[]::new), args.toArray());

                LOG.info("invoke method[{}.{}], took {}ms", api.getService(), api.getMethod(), System.currentTimeMillis() - start);

                return mapper.readValue(mapper.writeValueAsString(PojoUtils.realize(result, invokeMethod.getReturnType(), invokeMethod.getGenericReturnType())), Object.class);
            } else {
                LOG.warn("method[{}.{}] not found", api.getService(), api.getMethod());

                return "No such method[" + api.getMethod() + "] in service[" + api.getService() + "]";
            }
        } catch (Throwable t) {
            LOG.error("failed to invoke method[{}.{}]: ", api.getService(), api.getMethod(), t);

            return "Failed to invoke method[" + api.getService() + "." + api.getMethod() + "], cause: " + com.alibaba.dubbo.common.utils.StringUtils.toString(t);
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    private Method findMethod(Class<?> iface, String method, List<Object> args) throws ClassNotFoundException {
        Method[] methods = iface.getMethods();
        List<Method> sameSignatureMethods = new ArrayList<>();
        int size = args.size();
        for (Method m : methods) {
            if (m.getName().equals(method) && m.getParameterCount() == size) {
                sameSignatureMethods.add(m);
            }
        }

        size = sameSignatureMethods.size();
        if (size == 1) {
            return sameSignatureMethods.get(0);
        }

        if (size > 1) {
            for (Method m : sameSignatureMethods) {
                if (isMatch(m.getParameterTypes(), args)) {
                    return m;
                }
            }
        }

        return null;
    }

    private boolean isMatch(Class<?>[] types, List<Object> args) throws ClassNotFoundException {
        for (int i = 0, length = types.length; i < length; i++) {
            Class<?> type = types[i];
            Object arg = args.get(i);

            if (arg == null) {
                if (type.isPrimitive()) {
                    throw new NullPointerException(String.format("The type of No.%d parameter is primitive(%s), but the value passed is null.", i + 1, type.getName()));
                }

                continue;
            }

            if (ReflectUtils.isPrimitive(arg.getClass())) {
                if (!ReflectUtils.isPrimitive(type)) {
                    return false;
                }
            } else if (arg instanceof Map) {
                String name = (String) ((Map<?, ?>) arg).get("class");
                Class<?> cls = arg.getClass();
                if (name != null && name.length() > 0) {
                    cls = ClassHelper.forName(name);
                }

                if (!type.isAssignableFrom(cls)) {
                    return false;
                }
            } else if (arg instanceof Collection) {
                if (!type.isArray() && !type.isAssignableFrom(arg.getClass())) {
                    return false;
                }
            } else {
                if (!type.isAssignableFrom(arg.getClass())) {
                    return false;
                }
            }
        }

        return true;
    }
}
