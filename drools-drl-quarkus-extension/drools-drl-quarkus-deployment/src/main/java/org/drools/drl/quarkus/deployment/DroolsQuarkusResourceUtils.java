/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drools.drl.quarkus.deployment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.vertx.http.deployment.spi.AdditionalStaticResourceBuildItem;
import org.drools.model.project.codegen.GeneratedFile;
import org.drools.model.project.codegen.GeneratedFileType;
import org.drools.model.project.codegen.context.AppPaths;
import org.drools.model.project.codegen.context.DroolsModelBuildContext;
import org.drools.model.project.codegen.context.impl.QuarkusDroolsModelBuildContext;
import org.drools.modelcompiler.builder.JavaParserCompiler;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.kie.memorycompiler.JavaCompilerSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.drools.model.project.codegen.context.AppPaths.BuildTool.findBuildTool;
import static org.kie.memorycompiler.KieMemoryCompiler.compileNoLoad;

/**
 * Utility class to aggregate and share resource handling in Drools/Kogito extensions
 */
public class DroolsQuarkusResourceUtils {

    static final String HOT_RELOAD_SUPPORT_PACKAGE = "org.kie.kogito.app";
    static final String HOT_RELOAD_SUPPORT_CLASS = "HotReloadSupportClass";
    static final String HOT_RELOAD_SUPPORT_FQN = HOT_RELOAD_SUPPORT_PACKAGE + "." + HOT_RELOAD_SUPPORT_CLASS;
    static final String HOT_RELOAD_SUPPORT_PATH = HOT_RELOAD_SUPPORT_FQN.replace('.', '/');

    private DroolsQuarkusResourceUtils() {
        // utility class
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DroolsQuarkusResourceUtils.class);

    // since quarkus-maven-plugin is later phase of maven-resources-plugin,
    // need to manually late-provide the resource in the expected location for quarkus:dev phase --so not: writeGeneratedFile( f, resourcePath );
    private static final GeneratedFileWriter.Builder generatedFileWriterBuilder =
            new GeneratedFileWriter.Builder(
                    "target/classes",
                    System.getProperty("drools.codegen.sources.directory", "target/generated-sources/drools/"),
                    System.getProperty("drools.codegen.resources.directory", "target/generated-resources/drools/"),
                    "target/generated-sources/drools/");

    public static DroolsModelBuildContext createDroolsBuildContext(Path outputTarget, Iterable<Path> paths, IndexView index) {
        // scan and parse paths
        AppPaths.BuildTool buildTool = findBuildTool();
        AppPaths appPaths = AppPaths.fromQuarkus(outputTarget, paths, buildTool);
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        DroolsModelBuildContext context = QuarkusDroolsModelBuildContext.builder()
                .withClassLoader(classLoader)
                .withClassAvailabilityResolver(className -> classAvailabilityResolver(classLoader, index, className))
                .withAppPaths(appPaths)
                .build();

        return context;
    }

    /**
     * Verify if a class is available. First uses jandex indexes, then fallback on classLoader
     *
     * @param classLoader
     * @param className
     * @return
     */
    private static boolean classAvailabilityResolver(ClassLoader classLoader, IndexView index, String className) {
        if (index != null) {
            DotName classDotName = DotName.createSimple(className);
            boolean classFound = !index.getAnnotations(classDotName).isEmpty() ||
                    index.getClassByName(classDotName) != null;
            if (classFound) {
                return true;
            }
        }
        try {
            classLoader.loadClass(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static void dumpFilesToDisk(AppPaths appPaths, Collection<GeneratedFile> generatedFiles) {
        generatedFileWriterBuilder
                .build(appPaths.getFirstProjectPath())
                .writeAll(generatedFiles);
    }

    public static void registerResources(Collection<GeneratedFile> generatedFiles,
            BuildProducer<AdditionalStaticResourceBuildItem> staticResProducer,
            BuildProducer<NativeImageResourceBuildItem> resource,
            BuildProducer<GeneratedResourceBuildItem> genResBI) {
        for (GeneratedFile f : generatedFiles) {
            if (f.category() == GeneratedFileType.Category.INTERNAL_RESOURCE || f.category() == GeneratedFileType.Category.STATIC_HTTP_RESOURCE) {
                genResBI.produce(new GeneratedResourceBuildItem(f.relativePath(), f.contents(), true));
                resource.produce(new NativeImageResourceBuildItem(f.relativePath()));
            }
            if (f.category() == GeneratedFileType.Category.STATIC_HTTP_RESOURCE) {
                String resoucePath = f.relativePath().substring(GeneratedFile.META_INF_RESOURCES.length() - 1); // keep '/' at the beginning
                staticResProducer.produce(new AdditionalStaticResourceBuildItem(resoucePath, false));
            }
        }
    }

    public static Collection<GeneratedBeanBuildItem> compileGeneratedSources( DroolsModelBuildContext context, Collection<ResolvedDependency> dependencies,
                                                                              Collection<GeneratedFile> generatedFiles, boolean useDebugSymbols) {
        Map<String, String> sourcesMap = getSourceMap(generatedFiles);
        if (sourcesMap.isEmpty()) {
            LOGGER.info("No Java source to compile");
            return Collections.emptyList();
        }

        JavaCompilerSettings compilerSettings = createJavaCompilerSettings(context, dependencies, useDebugSymbols);
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        return makeBuildItems( compileNoLoad(sourcesMap, classLoader, compilerSettings) );
    }

    private static JavaCompilerSettings createJavaCompilerSettings(DroolsModelBuildContext context, Collection<ResolvedDependency> dependencies, boolean useDebugSymbols) {
        JavaCompilerSettings compilerSettings = JavaParserCompiler.getCompiler().createDefaultSettings();
        compilerSettings.addOption("-proc:none"); // force disable annotation processing
        if (useDebugSymbols) {
            compilerSettings.addOption("-g");
            compilerSettings.addOption("-parameters");
        }
        for (Path classPath : context.getAppPaths().getClassesPaths()) {
            compilerSettings.addClasspath(classPath.toFile());
        }
        for (ResolvedDependency i : dependencies) {
            compilerSettings.addClasspath(i.getResolvedPaths().getSinglePath().toFile());
        }
        return compilerSettings;
    }

    private static Map<String, String> getSourceMap(Collection<GeneratedFile> generatedFiles) {
        Map<String, String> sourcesMap = new HashMap<>();
        for (GeneratedFile javaFile : generatedFiles) {
            if (javaFile.category() == GeneratedFileType.Category.SOURCE) {
                sourcesMap.put(toClassName(javaFile.relativePath()), new String(javaFile.contents()));
            }
        }
        return sourcesMap;
    }

    private static Collection<GeneratedBeanBuildItem> makeBuildItems(Map<String, byte[]> byteCodeMap) {
        Collection<GeneratedBeanBuildItem> buildItems = new ArrayList<>();
        for (Map.Entry<String, byte[]> byteCode : byteCodeMap.entrySet()) {
            buildItems.add(new GeneratedBeanBuildItem(byteCode.getKey(), byteCode.getValue()));
        }
        return buildItems;
    }

    public static String toClassName(String sourceName) {
        if (sourceName.startsWith("./")) {
            sourceName = sourceName.substring(2);
        }
        if (sourceName.endsWith(".java")) {
            sourceName = sourceName.substring(0, sourceName.length() - 5);
        } else if (sourceName.endsWith(".class")) {
            sourceName = sourceName.substring(0, sourceName.length() - 6);
        }
        return sourceName.replace('/', '.').replace('\\', '.');
    }

    static String getHotReloadSupportSource() {
        return "package " + HOT_RELOAD_SUPPORT_PACKAGE + ";\n" +
                "@io.quarkus.runtime.Startup()\n" +
                "public class " + HOT_RELOAD_SUPPORT_CLASS + " {\n" +
                "private static final String ID = \"" + UUID.randomUUID() + "\";\n" +
                "}";
    }
}