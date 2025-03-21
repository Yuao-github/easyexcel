/*
 * Copyright (C) 2020 The zfoo Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.zfoo.storage.manager;

import com.zfoo.protocol.collection.CollectionUtils;
import com.zfoo.protocol.exception.ExceptionUtils;
import com.zfoo.protocol.exception.RunException;
import com.zfoo.protocol.util.ClassUtils;
import com.zfoo.protocol.util.FileUtils;
import com.zfoo.protocol.util.ReflectionUtils;
import com.zfoo.protocol.util.StringUtils;
import com.zfoo.storage.StorageContext;
import com.zfoo.storage.model.anno.Id;
import com.zfoo.storage.model.anno.ResInjection;
import com.zfoo.storage.model.config.StorageConfig;
import com.zfoo.storage.model.resource.ResourceEnum;
import com.zfoo.storage.model.vo.ResourceDef;
import com.zfoo.storage.model.vo.Storage;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.util.ResourceUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author godotg
 * @version 4.0
 */
public class StorageManager implements IStorageManager {

    // ANT通配符有三种, ? :匹配任何单字符; * :匹配0或者任意数量的字符; ** :匹配0或者更多的目录
    // 1. /project/*.a	匹配项目根路径下所有在project路径下的.a文件
    // 2. /project/p?ttern	匹配项目根路径下 /project/pattern 和 /app/pXttern,但是不包括/app/pttern
    // 3. /**/example	匹配项目根路径下 /project/example, /project/foow/example, 和 /example
    // 4. /project/**/dir/file.*	匹配项目根路径下/project/dir/file.jsp, /project/foow/dir/file.html,/project/foow/bar/dir/file.pdf
    // 5. /**/*.jsp	匹配项目根路径下任何的.jsp 文件
    // classpath：和classpath*： 的区别，前者只会第一个加载到的类，后者会加载所有的类，包括jar文件下的类
    private static final String SUFFIX_PATTERN = "**/*.class";

    private StorageConfig storageConfig;

    /**
     * 在当前项目被依赖注入，被使用的Storage
     */
    private final Map<Class<?>, Storage<?, ?>> storageMap = new HashMap<>();

    public StorageConfig getStorageConfig() {
        return storageConfig;
    }

    public void setStorageConfig(StorageConfig storageConfig) {
        this.storageConfig = storageConfig;
    }

    @Override
    public void initBefore() {
        var resourceDefinitionMap = new HashMap<Class<?>, ResourceDef>();

        // 扫描Excel的class类文件
        var clazzNameSet = scanResourceAnno(storageConfig.getScanPackages());

        // 通过class类文件扫描excel文件地址
        for (var clazzName : clazzNameSet) {
            Class<?> resourceClazz;
            try {
                resourceClazz = Class.forName(clazzName);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(StringUtils.format("无法获取资源类[{}]", clazzName));
            }

            var resourceFile = scanResourceFile(resourceClazz);
            ResourceDef resourceDef = new ResourceDef(resourceClazz, resourceFile);
            if (resourceDefinitionMap.containsKey(resourceClazz)) {
                throw new RuntimeException(StringUtils.format("类的资源定义[{}]已经存在[{}]", resourceClazz, resourceDef));
            }
            resourceDefinitionMap.put(resourceClazz, resourceDef);
        }

        // 检查class字段是否合法
        if (!storageConfig.isWriteable()) {
            var allRelevantClass = new HashSet<Class<?>>();
            resourceDefinitionMap.values().forEach(it -> allRelevantClass.addAll(ClassUtils.relevantClass(it.getClazz())));
            for (var clazz : allRelevantClass) {
                var fieldList = ReflectionUtils.notStaticAndTransientFields(clazz);
                for (var field : fieldList) {
                    if (Modifier.isPublic(field.getModifiers())) {
                        throw new RunException("因为静态资源类是不能被修改的，资源类[class:{}]的属性[filed:{}]不能被public修饰，用private修饰或者开启配置writeable属性", clazz, field.getName());
                    }

                    var setMethodName = StringUtils.EMPTY;
                    try {
                        setMethodName = ReflectionUtils.fieldToSetMethod(clazz, field);
                    } catch (Exception e) {
                        // 没有setMethod是正确的
                    }
                    if (StringUtils.isNotBlank(setMethodName)) {
                        throw new RunException("因为静态资源类是不能被修改的，资源类[class:{}]的属性[filed:{}]不能含有set方法[{}]，删除set方法或者开启配置writeable属性", clazz, field.getName(), setMethodName);
                    }
                }
            }
        }

        try {
            for (var definition : resourceDefinitionMap.values()) {
                var clazz = definition.getClazz();
                var resource = definition.getResource();
                var fileExtName = FileUtils.fileExtName(resource.getFilename());
                Storage<?, ?> storage = new Storage<>();
                storage.init(resource.getInputStream(), definition.getClazz(), fileExtName);
                storageMap.putIfAbsent(clazz, storage);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void inject() {
        var applicationContext = StorageContext.getApplicationContext();
        var beanNames = applicationContext.getBeanDefinitionNames();

        for (var beanName : beanNames) {
            var bean = applicationContext.getBean(beanName);

            ReflectionUtils.filterFieldsInClass(bean.getClass()
                    , field -> field.isAnnotationPresent(ResInjection.class)
                    , field -> {
                        Type type = field.getGenericType();

                        if (!(type instanceof ParameterizedType)) {
                            throw new RuntimeException(StringUtils.format("[bean:{}]类型声明不正确，不是泛型类", bean.getClass().getSimpleName()));
                        }

                        Type[] types = ((ParameterizedType) type).getActualTypeArguments();

                        // @ResInjection
                        // Storage<Integer, ActivityResource> resources;
                        Class<?> keyClazz = (Class<?>) types[0];

                        Class<?> resourceClazz = (Class<?>) types[1];

                        Storage<?, ?> storage = storageMap.get(resourceClazz);

                        if (storage == null) {
                            throw new RuntimeException(StringUtils.format("静态类资源[resource:{}]不存在", resourceClazz.getSimpleName()));
                        }

                        Field[] idFields = ReflectionUtils.getFieldsByAnnoInPOJOClass(resourceClazz, Id.class);
                        if (idFields.length != 1) {
                            throw new RuntimeException(StringUtils.format("静态类资源[resource:{}]配置没有注解id", resourceClazz.getSimpleName()));
                        }

                        if (!keyClazz.getSimpleName().toLowerCase().contains(idFields[0].getType().getSimpleName().toLowerCase())) {
                            throw new RuntimeException(StringUtils.format("注入静态类配置资源[storage:{}]的[key:{}]类型和泛型类型[type:{}]不匹配"
                                    , resourceClazz.getSimpleName(), idFields[0].getType().getSimpleName(), keyClazz.getSimpleName()));
                        }

                        ReflectionUtils.makeAccessible(field);
                        ReflectionUtils.setField(field, bean, storage);
                        storage.setRecycle(false);
                    });
        }
    }

    @Override
    public void initAfter() {
        if (storageConfig.isRecycle()) {
            storageMap.entrySet().stream()
                    .filter(it -> it.getValue().isRecycle())
                    .map(it -> it.getValue())
                    .forEach(it -> it.recycleStorage());
        } else {
            storageMap.entrySet().stream()
                    .map(it -> it.getValue())
                    .forEach(it -> it.setRecycle(false));
        }
    }

    @Override
    public Storage<?, ?> getStorage(Class<?> clazz) {
        var storage = storageMap.get(clazz);
        if (storage == null) {
            throw new RunException("没有定义[{}]的Storage，无法获取", clazz.getCanonicalName());
        }
        if (storage.isRecycle()) {
            throw new RunException("Storage没有使用[{}]，为了节省内存提前释放了它；只有使用ResInjection注解的Storage才能被动态获取或者关闭配置recycle属性", clazz.getCanonicalName());
        }
        return storage;
    }

    @Override
    public Map<Class<?>, Storage<?, ?>> storageMap() {
        return storageMap;
    }

    @Override
    public void updateStorage(Class<?> clazz, Storage<?, ?> storage) {
        storageMap.put(clazz, storage);
    }

    @Override
    public StorageConfig storageConfig() {
        return getStorageConfig();
    }

    private Set<String> scanResourceAnno(String[] scanPackages) {
        var resourcePatternResolver = new PathMatchingResourcePatternResolver();
        var metadataReaderFactory = new CachingMetadataReaderFactory(resourcePatternResolver);

        try {
            var result = new HashSet<String>();
            for(var scanPackage:scanPackages) {
                var packageSearchPath = ResourceUtils.CLASSPATH_URL_PREFIX + scanPackage.replace(StringUtils.PERIOD, StringUtils.SLASH) + StringUtils.SLASH + SUFFIX_PATTERN;
                var resources = resourcePatternResolver.getResources(packageSearchPath);
                var resourceName = com.zfoo.storage.model.anno.Resource.class.getName();
                for (var resource : resources) {
                    if (resource.isReadable()) {
                        var metadataReader = metadataReaderFactory.getMetadataReader(resource);
                        var annoMeta = metadataReader.getAnnotationMetadata();
                        if (annoMeta.hasAnnotation(resourceName)) {
                            ClassMetadata clazzMeta = metadataReader.getClassMetadata();
                            result.add(clazzMeta.getClassName());
                        }
                    }
                }
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("无法读取资源信息:" + e);
        }
    }

    private Resource scanResourceFile(Class<?> clazz) {
        var resourcePatternResolver = new PathMatchingResourcePatternResolver();
        var metadataReaderFactory = new CachingMetadataReaderFactory(resourcePatternResolver);

        try {
            var resourceDefSet = new HashSet<ResourceDef>();
            for(var resourceLocation: storageConfig.getResourceLocations()) {
                var packageSearchPath = StringUtils.format("{}/**/{}.*", resourceLocation, clazz.getSimpleName());
                packageSearchPath = packageSearchPath.replaceAll("//", "/");
                try {
                    for(var resource :resourcePatternResolver.getResources(packageSearchPath)){
                        if(StringUtils.suffixMatch(resource.getFilename(), ResourceEnum.CSV.getType())
                                ||StringUtils.suffixMatch(resource.getFilename(), ResourceEnum.EXCEL_XLS.getType())
                                ||StringUtils.suffixMatch(resource.getFilename(), ResourceEnum.EXCEL_XLSX.getType())
                                ||StringUtils.suffixMatch(resource.getFilename(), ResourceEnum.JSON.getType()))
                        resourceDefSet.add(new ResourceDef(clazz,resource));
                    }
                } catch (Exception e) {
                    // do nothing
                }

                // 通配符无法匹配根目录，所以如果找不到，再从根目录查找一遍
                if (resourceDefSet.isEmpty()) {
                    packageSearchPath = StringUtils.format("{}/{}.*", resourceLocation, clazz.getSimpleName());
                    packageSearchPath = packageSearchPath.replaceAll("//", "/");
                    for(var resource :resourcePatternResolver.getResources(packageSearchPath)){
                        if(StringUtils.suffixMatch(resource.getFilename(), ResourceEnum.CSV.getType())
                                ||StringUtils.suffixMatch(resource.getFilename(), ResourceEnum.EXCEL_XLS.getType())
                                ||StringUtils.suffixMatch(resource.getFilename(), ResourceEnum.EXCEL_XLSX.getType())
                                ||StringUtils.suffixMatch(resource.getFilename(), ResourceEnum.JSON.getType()))
                            resourceDefSet.add(new ResourceDef(clazz,resource));
                    }
                }
            }

            if (CollectionUtils.isEmpty(resourceDefSet)) {
                throw new RuntimeException(StringUtils.format("资源类[class:{}]无法找到配置文件", clazz.getSimpleName()));
            } else if (resourceDefSet.size() > 1) {
                var resourceNames=new String[resourceDefSet.size()];
                var index=0;
                    for(var resourceDef:resourceDefSet){
                        resourceNames[index++]=resourceDef.getResource().getFile().getAbsolutePath();
                    }

                throw new RuntimeException(StringUtils.format("资源类[class:{}]找到重复的配置文件{}", clazz.getSimpleName(),StringUtils.stringArrayToString(resourceNames)));
            } else {
                return ((ResourceDef)(resourceDefSet.toArray()[0])).getResource();
            }

        } catch (IOException e) {
            throw new RuntimeException(ExceptionUtils.getMessage(e));
        }
    }
}
