package org.micro.lemon.proxy.dubbo;

import lombok.Getter;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.micro.lemon.common.LemonConfig;
import org.micro.lemon.common.LemonInvoke;
import org.micro.lemon.common.LemonStatusCode;
import org.micro.lemon.common.ServiceMapping;
import org.micro.lemon.common.config.OriginalConfig;
import org.micro.lemon.common.utils.NetUtils;
import org.micro.lemon.proxy.dubbo.metadata.MetadataCollectorFactory;
import org.micro.lemon.server.LemonContext;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.utils.ReferenceConfigCache;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.GenericService;
import org.micro.lemon.extension.Extension;
import org.micro.lemon.server.LemonRequest;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * DubboInvoke
 *
 * @author lry
 */
@Slf4j
@Getter
@Extension("dubbo")
public class DubboInvoke implements LemonInvoke {

    private static final String GROUP_KEY = "group";
    private static final String VERSION_KEY = "version";

    private LemonConfig lemonConfig;

    private URL serverUrl;
    private RegistryConfig registryConfig;
    private RegistryService registryService;
    private MetadataCollectorFactory metadataCollectorFactory;
    private RegistryServiceSubscribe registryServiceSubscribe;


    @Override
    public void initialize(LemonConfig lemonConfig) {
        this.lemonConfig = lemonConfig;

        // 创建注册中心配置
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setAddress(lemonConfig.getDubbo().getRegistryAddress());
        this.registryConfig = registryConfig;

        // 启动注册中心，并注册服务本身至注册中心
        URL url = URL.valueOf(lemonConfig.getRegistryAddress());
        RegistryFactory registryFactory = ExtensionLoader.getExtensionLoader(RegistryFactory.class).getExtension(url.getProtocol());
        this.registryService = registryFactory.getRegistry(url);
        this.serverUrl = new URL("http", NetUtils.getLocalHost(), lemonConfig.getPort(), lemonConfig.getApplication());
        registryService.register(serverUrl);

        this.registryServiceSubscribe = new RegistryServiceSubscribe();
        registryServiceSubscribe.initialize(registryService);

        // 启动Metadata数据收集器
        this.metadataCollectorFactory = MetadataCollectorFactory.INSTANCE;
        metadataCollectorFactory.initialize(lemonConfig);
    }

    @Override
    public LemonContext invoke(LemonContext lemonContext) {
        LemonRequest request = lemonContext.getRequest();
        OriginalConfig originalConfig = lemonConfig.getOriginal();

        // setter request header list
        for (Map.Entry<String, Object> entry : request.getHeaders().entrySet()) {
            // originalReqHeaders contains or starts with 'X-'
            if (originalConfig.getReqHeaders().contains(entry.getKey())
                    || entry.getKey().startsWith(LemonContext.HEADER_PREFIX)) {
                RpcContext.getContext().setAttachment(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        // call original remote
        ServiceMapping serviceMapping = parseServiceMapping(request);
        byte[] bytes = (byte[]) request.getContent();
        String body = new String(bytes, StandardCharsets.UTF_8);

        List<Object> paramValues = new ArrayList<>();
        paramValues.add(JSON.isValid(body) ? JSON.parse(bytes) : body);
        serviceMapping.setParamValues(paramValues.toArray(new Object[0]));

        GenericService genericService = buildGenericService(request, serviceMapping);
        Object result = genericService.$invoke(serviceMapping.getMethod(),
                serviceMapping.getParamTypes(), serviceMapping.getParamValues());

        // setter response header list
        Map<String, Object> headers = new HashMap<>();
        for (Map.Entry<String, String> entry : RpcContext.getContext().getAttachments().entrySet()) {
            headers.put(entry.getKey(), entry.getValue());
        }

        lemonContext.getResponse().addHeader(headers);
        lemonContext.getResponse().setContent(result);
        return lemonContext;
    }

    @Override
    public LemonStatusCode failure(LemonContext context, Throwable throwable) {
        if (throwable instanceof RpcException) {
            RpcException e = (RpcException) throwable;
            if (e.isTimeout()) {
                return LemonStatusCode.CALL_ORIGINAL_TIMEOUT;
            } else if (e.isBiz()) {
                return LemonStatusCode.CALL_ORIGINAL_BIZ_ERROR;
            } else if (e.isNetwork()) {
                return LemonStatusCode.CALL_ORIGINAL_NETWORK_ERROR;
            } else if (e.isSerialization()) {
                return LemonStatusCode.CALL_ORIGINAL_SERIALIZATION;
            }
        }

        return LemonStatusCode.CALL_ORIGINAL_UNKNOWN;
    }

    @Override
    public void destroy() {
        if (registryService != null) {
            registryService.unregister(serverUrl);
        }
    }

    /**
     * The build {@link GenericService} by {@link ServiceMapping}
     *
     * @param request        {@link LemonRequest}
     * @param serviceMapping {@link ServiceMapping}
     * @return {@link GenericService}
     */
    private GenericService buildGenericService(LemonRequest request, ServiceMapping serviceMapping) {
        ReferenceConfig<GenericService> referenceConfig = new ReferenceConfig<>();
        referenceConfig.setApplication(new ApplicationConfig(serviceMapping.getApplication()));
        referenceConfig.setGroup(serviceMapping.getGroup());
        referenceConfig.setVersion(serviceMapping.getVersion());
        referenceConfig.setRegistry(registryConfig);
        referenceConfig.setInterface(serviceMapping.getServiceName());
        referenceConfig.setGeneric(true);

        if (serviceMapping.getParamTypes() == null) {
            metadataCollectorFactory.wrapperTypesFromMetadata(request, serviceMapping);
        }

        return ReferenceConfigCache.getCache().get(referenceConfig);
    }

    /**
     * The wrapper {@link ServiceMapping} by {@link LemonContext}
     *
     * @param request {@link LemonRequest}
     */
    private ServiceMapping parseServiceMapping(LemonRequest request) {
        List<String> paths = Arrays.asList(request.getContextPath().split(LemonContext.URL_DELIMITER));
        if (paths.size() != 5) {
            throw new IllegalArgumentException("Illegal Request");
        }

        ServiceMapping serviceMapping = new ServiceMapping();
        serviceMapping.setApplication(paths.get(2));
        serviceMapping.setService(paths.get(3));
        serviceMapping.setMethod(paths.get(4));

        // wrapper service name
        serviceMapping.setServiceName(this.getServiceName(serviceMapping));

        Map<String, Object> parameters = request.getHeaders();
        if (parameters.containsKey(GROUP_KEY)) {
            serviceMapping.setGroup(String.valueOf(parameters.get(GROUP_KEY)));
        }
        if (parameters.containsKey(VERSION_KEY)) {
            serviceMapping.setVersion(String.valueOf(parameters.get(VERSION_KEY)));
        }

        return serviceMapping;
    }

    /**
     * The get service name
     *
     * @param serviceMapping {@link ServiceMapping}
     * @return service name
     */
    private String getServiceName(ServiceMapping serviceMapping) {
        ConcurrentMap<String, String> serviceNames =
                registryServiceSubscribe.getServiceNames().get(serviceMapping.getApplication());
        if (serviceNames == null || serviceNames.isEmpty()) {
            return serviceMapping.getService();
        }

        String serviceName = serviceNames.get(serviceMapping.getService());
        if (serviceName == null || serviceName.length() == 0) {
            return serviceMapping.getService();
        }

        return serviceName;
    }

}