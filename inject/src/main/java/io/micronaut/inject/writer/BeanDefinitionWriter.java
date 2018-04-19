/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.inject.writer;

import io.micronaut.context.AbstractBeanDefinition;
import io.micronaut.context.AbstractParametrizedBeanDefinition;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.DefaultBeanContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanFactory;
import io.micronaut.inject.DisposableBeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.InitializingBeanDefinition;
import io.micronaut.inject.ValidatedBeanDefinition;
import io.micronaut.inject.annotation.AnnotationMetadataWriter;
import io.micronaut.inject.annotation.DefaultAnnotationMetadata;
import io.micronaut.inject.configuration.ConfigurationMetadataBuilder;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

import javax.inject.Qualifier;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * <p>Responsible for building {@link BeanDefinition} instances at compile time. Uses ASM build the class definition.</p>
 * <p>
 * <p>Should be used from AST frameworks to build bean definitions from source code data.</p>
 * <p>
 * <p>For example:</p>
 *
 * <pre>
 *     {@code
 *
 *          BeanDefinitionWriter writer = new BeanDefinitionWriter("my.package", "MyClass", "javax.inject.Singleton", true)
 *          writer.visitBeanDefinitionConstructor()
 *          writer.visitFieldInjectionPoint("my.Qualifier", false, "my.package.MyDependency", "myfield" )
 *          writer.visitBeanDefinitionEnd()
 *          writer.writeTo(new File(..))
 *     }
 * </pre>
 *
 * @author Graeme Rocher
 * @see BeanDefinition
 * @since 1.0
 */
public class BeanDefinitionWriter extends AbstractClassFileWriter implements BeanDefinitionVisitor {

    private static final org.objectweb.asm.commons.Method METHOD_GET_REQUIRED_METHOD = org.objectweb.asm.commons.Method.getMethod(ReflectionUtils.getRequiredInternalMethod(
            ReflectionUtils.class,
            "getRequiredMethod",
            Class.class,
            String.class,
            Class[].class
    ));

    private static final Constructor<AbstractBeanDefinition> CONSTRUCTOR_ABSTRACT_BEAN_DEFINITION = ReflectionUtils.findConstructor(
            AbstractBeanDefinition.class,
            Class.class,
            AnnotationMetadata.class,
            boolean.class,
            Argument[].class)
            .orElseThrow(() -> new ClassGenerationException("Invalid version of Micronaut present on the class path"));

    private static final org.objectweb.asm.commons.Method METHOD_CREATE_ARGUMENT_METHOD = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredInternalMethod(
                    Argument.class,
                    "of",
                    Method.class,
                    String.class,
                    int.class,
                    Class.class,
                    Argument[].class
            )
    );

    private static final org.objectweb.asm.commons.Method METHOD_CREATE_ARGUMENT_FIELD = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredInternalMethod(
                    Argument.class,
                    "of",
                    Field.class,
                    String.class,
                    Class.class,
                    Argument[].class
            )
    );

    private static final org.objectweb.asm.commons.Method METHOD_CREATE_ARGUMENT_SIMPLE = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredInternalMethod(
                    Argument.class,
                    "of",
                    Class.class,
                    String.class
            )
    );

    private static final org.objectweb.asm.commons.Method METHOD_CREATE_ARGUMENT_WITH_GENERICS = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredInternalMethod(
                    Argument.class,
                    "of",
                    Class.class,
                    String.class,
                    Argument[].class
            )
    );

    private static final org.objectweb.asm.commons.Method METHOD_CREATE_ARGUMENT_WITH_ANNOTATION_METADATA_GENERICS = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredInternalMethod(
                    Argument.class,
                    "of",
                    Class.class,
                    String.class,
                    AnnotationMetadata.class,
                    Argument[].class
            )
    );

    private static final Method POST_CONSTRUCT_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "postConstruct", BeanResolutionContext.class, BeanContext.class, Object.class);

    private static final Method INJECT_BEAN_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "injectBean", BeanResolutionContext.class, BeanContext.class, Object.class);

    private static final Method PRE_DESTROY_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "preDestroy", BeanResolutionContext.class, BeanContext.class, Object.class);

    private static final Method ADD_FIELD_INJECTION_POINT_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addInjectionPoint", Field.class, Annotation.class, boolean.class);

    private static final Method ADD_METHOD_INJECTION_POINT_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addInjectionPoint", Class.class, String.class, Argument[].class, AnnotationMetadata.class, boolean.class);

    private static final Method ADD_POST_CONSTRUCT_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addPostConstruct", Class.class, String.class, Argument[].class, AnnotationMetadata.class, boolean.class);

    private static final Method ADD_PRE_DESTROY_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addPreDestroy", Class.class, String.class, Argument[].class, AnnotationMetadata.class, boolean.class);

    private static final Method ADD_SETTER_INJECTION_POINT_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addInjectionPoint", Field.class, Method.class, Argument.class, boolean.class);

    private static final Method ADD_EXECUTABLE_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addExecutableMethod", ExecutableMethod.class);

    private static final Method GET_BEAN_FOR_CONSTRUCTOR_ARGUMENT = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "getBeanForConstructorArgument", BeanResolutionContext.class, BeanContext.class, int.class);

    private static final Method GET_VALUE_FOR_CONSTRUCTOR_ARGUMENT = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "getValueForConstructorArgument", BeanResolutionContext.class, BeanContext.class, int.class);

    private static final Method GET_BEAN_FOR_FIELD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "getBeanForField", BeanResolutionContext.class, BeanContext.class, int.class);

    private static final Method GET_VALUE_FOR_FIELD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "getValueForField", BeanResolutionContext.class, BeanContext.class, int.class);

    private static final Method GET_VALUE_FOR_PATH = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "getValueForPath", BeanResolutionContext.class, BeanContext.class, Argument.class, String[].class);

    private static final Method CONTAINS_VALUE_FOR_FIELD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "containsValueForField", BeanResolutionContext.class, BeanContext.class, int.class);

    private static final Method CONTAINS_PROPERTIES_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "containsProperties", BeanResolutionContext.class, BeanContext.class);

    private static final Method GET_BEAN_FOR_METHOD_ARGUMENT = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "getBeanForMethodArgument", BeanResolutionContext.class, BeanContext.class, int.class, int.class);

    private static final Method GET_VALUE_FOR_METHOD_ARGUMENT = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "getValueForMethodArgument", BeanResolutionContext.class, BeanContext.class, int.class, int.class);

    private static final Method CONTAINS_VALUE_FOR_METHOD_ARGUMENT = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "containsValueForMethodArgument", BeanResolutionContext.class, BeanContext.class, int.class, int.class);

    private static final org.objectweb.asm.commons.Method BEAN_DEFINITION_CLASS_CONSTRUCTOR = new org.objectweb.asm.commons.Method(CONSTRUCTOR_NAME, getConstructorDescriptor(
            Class.class, AnnotationMetadata.class, boolean.class, Argument[].class
    ));

    private static final org.objectweb.asm.commons.Method BEAN_DEFINITION_METHOD_CONSTRUCTOR = new org.objectweb.asm.commons.Method(CONSTRUCTOR_NAME, getConstructorDescriptor(
            Method.class, Argument[].class
    ));

    private static final Type TYPE_ABSTRACT_BEAN_DEFINITION = Type.getType(AbstractBeanDefinition.class);
    private static final Type TYPE_OPTIONAL = Type.getType(Optional.class);
    private static final Type TYPE_ABSTRACT_PARAMETRIZED_BEAN_DEFINITION = Type.getType(AbstractParametrizedBeanDefinition.class);
    private static final String FIELD_PROXIED_CONSTRUCTOR = "$PROXIED_CONSTRUCTOR";
    private final ClassWriter classWriter;
    private final String beanFullClassName;
    private final String beanDefinitionName;
    private final String beanDefinitionInternalName;
    private final Type beanType;
    private final Type providedType;
    private final Set<Class> interfaceTypes;
    private final Map<String, ExecutableMethodWriter> methodExecutors = new LinkedHashMap<>();
    private final String providedBeanClassName;
    private final String packageName;
    private final String beanSimpleClassName;
    private final Type beanDefinitionType;
    private final boolean isInterface;
    private final boolean isConfigurationProperties;
    private GeneratorAdapter constructorVisitor;
    private GeneratorAdapter buildMethodVisitor;
    private GeneratorAdapter injectMethodVisitor;
    private Label injectEnd = null;
    private GeneratorAdapter preDestroyMethodVisitor;
    private GeneratorAdapter postConstructMethodVisitor;
    private int methodExecutorIndex = 0;
    private int constructorLocalVariableCount = 1;
    private int currentFieldIndex = 0;
    private int currentMethodIndex = 0;

    // 0 is this, while 1,2 and 3 are the first 3 parameters in the "build" method signature. See BeanFactory
    private int buildMethodLocalCount = 4;

    // 0 is this, while 1,2 and 3 are the first 3 parameters in the "injectBean" method signature. See AbstractBeanDefinition
    private int injectMethodLocalCount = 4;

    // 0 is this, while 1,2 and 3 are the first 3 parameters in the "initialize" method signature. See InitializingBeanDefinition
    private int postConstructMethodLocalCount = 4;

    // 0 is this, while 1,2 and 3 are the first 3 parameters in the "dispose" method signature. See DisposableBeanDefinition
    private int preDestroyMethodLocalCount = 4;

    // the instance being built position in the index
    private int buildInstanceIndex;
    private int argsIndex = -1;
    private int injectInstanceIndex;
    private int postConstructInstanceIndex;
    private int preDestroyInstanceIndex;
    private boolean beanFinalized = false;
    private Type superType = TYPE_ABSTRACT_BEAN_DEFINITION;
    private boolean isSuperFactory = false;
    private final AnnotationMetadata annotationMetadata;
    private ConfigBuilderState currentConfigBuilderState;
    private int optionalInstanceIndex;
    private boolean preprocessMethods = false;
    private GeneratorAdapter staticInit;
    // in the case of producing proxied constructors this field stores the number of arguments of the original constructor
    private int proxiedArgumentCount = -1;

    /**
     * Creates a bean definition writer
     *
     * @param packageName The package name of the bean
     * @param className   The class name, without the package, of the bean
     */
    public BeanDefinitionWriter(String packageName,
                                String className,
                                AnnotationMetadata annotationMetadata) {
        this(packageName, className, packageName + '.' + className, false, annotationMetadata);
    }

    /**
     * Creates a bean definition writer
     *
     * @param packageName       The package name of the bean
     * @param className         The class name, without the package, of the bean
     * @param providedClassName The type this bean definition provides, in this case where the bean implements {@link javax.inject.Provider}
     * @param isInterface       Is the type an interface
     */
    public BeanDefinitionWriter(String packageName,
                                String className,
                                String providedClassName,
                                boolean isInterface,
                                AnnotationMetadata annotationMetadata) {
        this(packageName, className, packageName + ".$" + className + "Definition", providedClassName, isInterface, annotationMetadata);
    }

    /**
     * Creates a bean definition writer
     *
     * @param packageName        The package name of the bean
     * @param className          The class name, without the package, of the bean
     * @param beanDefinitionName The name of the bean definition
     * @param providedClassName  The type this bean definition provides, which differs from the class name in the case of factory beans
     * @param isInterface        Whether the provided type is an interface
     * @param annotationMetadata The annotation metadata
     */
    public BeanDefinitionWriter(String packageName,
                                String className,
                                String beanDefinitionName,
                                String providedClassName,
                                boolean isInterface,
                                AnnotationMetadata annotationMetadata) {
        this.classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        this.packageName = packageName;
        this.isInterface = isInterface;
        this.beanFullClassName = packageName + '.' + className;
        this.annotationMetadata = annotationMetadata;
        this.beanSimpleClassName = className;
        this.providedBeanClassName = providedClassName;
        this.beanDefinitionName = beanDefinitionName;
        this.beanDefinitionType = getTypeReference(this.beanDefinitionName);
        this.beanType = getTypeReference(beanFullClassName);
        this.providedType = getTypeReference(providedBeanClassName);
        this.beanDefinitionInternalName = getInternalName(this.beanDefinitionName);
        this.interfaceTypes = new HashSet<>();
        this.interfaceTypes.add(BeanFactory.class);
        this.isConfigurationProperties = annotationMetadata.hasDeclaredStereotype(ConfigurationProperties.class);
    }

    /**
     * @return The underlying class writer
     */
    public ClassVisitor getClassWriter() {
        return classWriter;
    }

    @Override
    public boolean isInterface() {
        return isInterface;
    }

    @Override
    public boolean isSingleton() {
        return annotationMetadata.hasDeclaredStereotype(Singleton.class);
    }

    @Override
    public void visitBeanDefinitionInterface(Class<? extends BeanDefinition> interfaceType) {
        this.interfaceTypes.add(interfaceType);
    }

    @Override
    public void visitSuperBeanDefinition(String name) {
        this.superType = getTypeReference(name);
    }

    @Override
    public void visitSuperBeanDefinitionFactory(String beanName) {
        visitSuperBeanDefinition(beanName);
        this.isSuperFactory = true;
    }

    @Override
    public String getBeanTypeName() {
        return beanFullClassName;
    }

    @Override
    public Type getProvidedType() {
        return providedType;
    }

    @Override
    public void setValidated(boolean validated) {
        if (validated) {
            this.interfaceTypes.add(ValidatedBeanDefinition.class);
        } else {
            this.interfaceTypes.remove(ValidatedBeanDefinition.class);
        }
    }

    @Override
    public boolean isValidated() {
        return this.interfaceTypes.contains(ValidatedBeanDefinition.class);
    }

    @Override
    public String getBeanDefinitionName() {
        return beanDefinitionName;
    }

    /**
     * @return The name of the bean definition class
     */
    public String getBeanDefinitionClassFile() {
        String className = getBeanDefinitionName();
        return getClassFileName(className);
    }

    /**
     * <p>In the case where the produced class is produced by a factory method annotated with {@link Bean} this method should be called</p>
     *
     * @param factoryClass               The factory class
     * @param methodName                 The method name
     * @param argumentTypes              The arguments to the method
     * @param argumentAnnotationMetadata The argument annotation metadata
     * @param genericTypes               The generic types for the method parameters
     */
    public void visitBeanFactoryMethod(Object factoryClass,
                                       String methodName,
                                       Map<String, Object> argumentTypes,
                                       Map<String, AnnotationMetadata> argumentAnnotationMetadata,
                                       Map<String, Map<String, Object>> genericTypes) {
        if (constructorVisitor != null) {
            throw new IllegalStateException("Only a single call to visitBeanFactoryMethod(..) is permitted");
        } else {
            // now prepare the implementation of the build method. See BeanFactory interface
            visitBuildFactoryMethodDefinition(factoryClass, methodName, argumentTypes, argumentAnnotationMetadata);

            // now implement the constructor
            buildFactoryMethodClassConstructor(factoryClass, methodName, argumentTypes, argumentAnnotationMetadata, genericTypes);

            // now override the injectBean method
            visitInjectMethodDefinition();
        }
    }

    /**
     * Visits the constructor used to create the bean definition.
     *
     * @param annotationMetadata The annotation metadata for the constructor
     * @param requiresReflection Whether invoking the constructor requires reflection
     * @param argumentTypes              The argument type names for each parameter
     * @param argumentAnnotationMetadata The qualifier type names for each parameter
     * @param genericTypes               The generic types for each parameter
     */
    @Override
    public void visitBeanDefinitionConstructor(AnnotationMetadata annotationMetadata,
                                               boolean requiresReflection,
                                               Map<String, Object> argumentTypes,
                                               Map<String, AnnotationMetadata> argumentAnnotationMetadata,
                                               Map<String, Map<String, Object>> genericTypes) {
        if (constructorVisitor == null) {
            // first build the constructor
            visitBeanDefinitionConstructorInternal(
                    annotationMetadata,
                    requiresReflection,
                    argumentTypes,
                    argumentAnnotationMetadata,
                    genericTypes);

            // now prepare the implementation of the build method. See BeanFactory interface
            visitBuildMethodDefinition(argumentTypes, argumentAnnotationMetadata);

            // now override the injectBean method
            visitInjectMethodDefinition();
        }
    }

    @Override
    public void visitProxiedBeanDefinitionConstructor(
            Object declaringType,
            Map<String, Object> argumentTypes,
            Map<String, AnnotationMetadata> argumentAnnotationMetadata,
            Map<String, Map<String, Object>> genericTypes) {
        if (constructorVisitor != null) {
            throw new IllegalStateException("Call visitProxiedBeanDefinitionConstructor(..) before visitBeanDefinitionConstructor(..)");
        }

        GeneratorAdapter staticInit = this.staticInit = this.staticInit == null ? visitStaticInitializer(classWriter) : this.staticInit;

        Type proxiedType = getTypeReference(declaringType);

        this.proxiedArgumentCount = argumentTypes.size();
        pushInitializeConstructorField(staticInit, FIELD_PROXIED_CONSTRUCTOR, proxiedType, argumentTypes);
    }

    /**
     * Visits a no-args constructor used to create the bean definition.
     */
    @Override
    public void visitBeanDefinitionConstructor(AnnotationMetadata annotationMetadata,
                                               boolean requiresReflection) {
        if (constructorVisitor == null) {
            // first build the constructor
            visitBeanDefinitionConstructorInternal(annotationMetadata, requiresReflection, Collections.emptyMap(), null, null);

            // now prepare the implementation of the build method. See BeanFactory interface
            visitBuildMethodDefinition(Collections.emptyMap(), Collections.emptyMap());

            // now override the injectBean method
            visitInjectMethodDefinition();
        }
    }

    /**
     * Finalize the bean definition to the given output stream
     */
    @SuppressWarnings("Duplicates")
    @Override
    public void visitBeanDefinitionEnd() {
        if (classWriter == null) {
            throw new IllegalStateException("At least one called to visitBeanDefinitionConstructor(..) is required");
        }

        String[] interfaceInternalNames = new String[interfaceTypes.size()];
        Iterator<Class> j = interfaceTypes.iterator();
        for (int i = 0; i < interfaceInternalNames.length; i++) {
            interfaceInternalNames[i] = Type.getInternalName(j.next());
        }
        classWriter.visit(V1_8, ACC_PUBLIC,
                beanDefinitionInternalName,
                generateBeanDefSig(providedType.getInternalName()),
                isSuperFactory ? TYPE_ABSTRACT_BEAN_DEFINITION.getInternalName() : superType.getInternalName(),
                interfaceInternalNames);

        if (buildMethodVisitor == null) {
            throw new IllegalStateException("At least one call to visitBeanDefinitionConstructor() is required");
        }

        finalizeInjectMethod();
        finalizeBuildMethod();
        finalizeAnnotationMetadata();

        if (preprocessMethods) {
            GeneratorAdapter requiresMethodProcessing = startPublicMethod(classWriter, "requiresMethodProcessing", boolean.class.getName());
            requiresMethodProcessing.push(true);
            requiresMethodProcessing.visitInsn(IRETURN);
            requiresMethodProcessing.visitMaxs(1, 1);
            requiresMethodProcessing.visitEnd();
        }

        if (staticInit != null) {
            staticInit.visitInsn(RETURN);
            staticInit.visitMaxs(1, 1);
            staticInit.visitEnd();
        }
        constructorVisitor.visitInsn(RETURN);
        constructorVisitor.visitMaxs(DEFAULT_MAX_STACK, 1);
        if (buildMethodVisitor != null) {
            buildMethodVisitor.visitInsn(ARETURN);
            buildMethodVisitor.visitMaxs(DEFAULT_MAX_STACK, buildMethodLocalCount);
        }
        if (injectMethodVisitor != null) {
            injectMethodVisitor.visitMaxs(DEFAULT_MAX_STACK, injectMethodLocalCount);
        }
        if (postConstructMethodVisitor != null) {
            postConstructMethodVisitor.visitVarInsn(ALOAD, postConstructInstanceIndex);
            postConstructMethodVisitor.visitInsn(ARETURN);
            postConstructMethodVisitor.visitMaxs(DEFAULT_MAX_STACK, postConstructMethodLocalCount);
        }
        if (preDestroyMethodVisitor != null) {
            preDestroyMethodVisitor.visitVarInsn(ALOAD, preDestroyInstanceIndex);
            preDestroyMethodVisitor.visitInsn(ARETURN);
            preDestroyMethodVisitor.visitMaxs(DEFAULT_MAX_STACK, preDestroyMethodLocalCount);
        }

        classWriter.visitEnd();
        this.beanFinalized = true;
    }

    protected void finalizeAnnotationMetadata() {
        if (annotationMetadata != null) {
            GeneratorAdapter annotationMetadataMethod = startPublicMethod(classWriter, "getAnnotationMetadata", AnnotationMetadata.class.getName());
            annotationMetadataMethod.loadThis();
            annotationMetadataMethod.getStatic(getTypeReference(beanDefinitionName + BeanDefinitionReferenceWriter.REF_SUFFIX), AbstractAnnotationMetadataWriter.FIELD_ANNOTATION_METADATA, Type.getType(AnnotationMetadata.class));
            annotationMetadataMethod.returnValue();
            annotationMetadataMethod.visitMaxs(1, 1);
            annotationMetadataMethod.visitEnd();
        }
    }

    /**
     * @return The bytes of the class
     */
    public byte[] toByteArray() {
        if (!beanFinalized) {
            throw new IllegalStateException("Bean definition not finalized. Call visitBeanDefinitionEnd() first.");
        }
        return classWriter.toByteArray();
    }

    @Override
    public void accept(ClassWriterOutputVisitor visitor) throws IOException {
        try (OutputStream out = visitor.visitClass(getBeanDefinitionName())) {
            try {
                for (ExecutableMethodWriter methodWriter : methodExecutors.values()) {
                    methodWriter.accept(visitor);
                }
            } catch (RuntimeException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                } else {
                    throw e;
                }
            }
            out.write(toByteArray());
        }
    }

    @Override
    public void visitSetterInjectionPoint(Object declaringType,
                                          AnnotationMetadata fieldMetadata,
                                          boolean requiresReflection,
                                          Object fieldType,
                                          String fieldName,
                                          String setterName,
                                          Map<String, Object> genericTypes) {
        Type declaringTypeRef = getTypeReference(declaringType);

        addInjectionPointForSetterInternal(fieldMetadata, requiresReflection, fieldType, fieldName, setterName, genericTypes, declaringTypeRef);

        if (!requiresReflection) {
            resolveBeanOrValueForSetter(declaringTypeRef, setterName, fieldType, GET_BEAN_FOR_METHOD_ARGUMENT, false);

        }
        currentMethodIndex++;
    }

    @Override
    public void visitSetterValue(Object declaringType,
                                 AnnotationMetadata annotationMetadata,
                                 boolean requiresReflection,
                                 Object fieldType,
                                 String fieldName,
                                 String setterName,
                                 Map<String, Object> genericTypes,
                                 boolean isOptional) {
        Type declaringTypeRef = getTypeReference(declaringType);

        addInjectionPointForSetterInternal(annotationMetadata, requiresReflection, fieldType, fieldName, setterName, genericTypes, declaringTypeRef);

        if (!requiresReflection) {
            resolveBeanOrValueForSetter(declaringTypeRef, setterName, fieldType, GET_VALUE_FOR_METHOD_ARGUMENT, isOptional);
        }
        currentMethodIndex++;
    }

    @Override
    public void visitSetterValue(
            Object declaringType,
            AnnotationMetadata annotationMetadata,
            boolean requiresReflection,
            Object valueType,
            String setterName,
            Map<String, Object> genericTypes,
            AnnotationMetadata setterArgumentMetadata,
            boolean isOptional) {
        Type declaringTypeRef = getTypeReference(declaringType);

        // load 'this'
        constructorVisitor.visitVarInsn(ALOAD, 0);

        // 1st argument: The declaring type
        constructorVisitor.push(declaringTypeRef);

        // 2nd argument: The method name
        constructorVisitor.push(setterName);

        // 3rd argument: the argument types
        String propertyName = NameUtils.getPropertyNameForSetter(setterName);
        pushBuildArgumentsForMethod(
                constructorVisitor,
                Collections.singletonMap(propertyName, valueType),
                Collections.singletonMap(propertyName, setterArgumentMetadata),
                Collections.singletonMap(propertyName, genericTypes)
        );

        // 4th argument: The annotation metadata
        if (annotationMetadata == AnnotationMetadata.EMPTY_METADATA) {
            constructorVisitor.visitInsn(ACONST_NULL);
        } else {
            AnnotationMetadataWriter.instantiateNewMetadata(constructorVisitor, (DefaultAnnotationMetadata) annotationMetadata);
        }

        // 5th  argument to addInjectionPoint: do we need reflection?
        constructorVisitor.visitInsn(requiresReflection ? ICONST_1 : ICONST_0);

        // invoke add injection point method
        pushInvokeMethodOnSuperClass(constructorVisitor, ADD_METHOD_INJECTION_POINT_METHOD);

        if (!requiresReflection) {
            resolveBeanOrValueForSetter(declaringTypeRef, setterName, valueType, GET_VALUE_FOR_METHOD_ARGUMENT, isOptional);
        }
        currentMethodIndex++;

    }

    @Override
    public void visitPostConstructMethod(Object declaringType,
                                         boolean requiresReflection,
                                         Object returnType,
                                         String methodName,
                                         Map<String, Object> argumentTypes,
                                         Map<String, AnnotationMetadata> argumentAnnotationMetadata,
                                         Map<String, Map<String, Object>> genericTypes,
                                         AnnotationMetadata annotationMetadata) {
        visitPostConstructMethodDefinition();

        visitMethodInjectionPointInternal(
                declaringType,
                requiresReflection,
                returnType,
                methodName,
                argumentTypes,
                argumentAnnotationMetadata,
                genericTypes,
                this.annotationMetadata,
                constructorVisitor,
                postConstructMethodVisitor,
                postConstructInstanceIndex,
                ADD_POST_CONSTRUCT_METHOD);
    }


    /**
     * Visits a pre-destroy method injection point
     *
     * @param declaringType The declaring type of the method. Either a Class or a string representing the name of the type
     * @param methodName    The method name
     */
    public void visitPreDestroyMethod(Object declaringType,
                                      String methodName) {
        visitPreDestroyMethodDefinition();
        visitMethodInjectionPointInternal(
                declaringType,
                false,
                Void.TYPE,
                methodName,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                AnnotationMetadata.EMPTY_METADATA,
                constructorVisitor,
                preDestroyMethodVisitor,
                preDestroyInstanceIndex,
                ADD_PRE_DESTROY_METHOD);
    }

    @Override
    public void visitPreDestroyMethod(Object declaringType,
                                      boolean requiresReflection,
                                      Object returnType,
                                      String methodName,
                                      Map<String, Object> argumentTypes,
                                      Map<String, AnnotationMetadata> argumentAnnotationMetadata,
                                      Map<String, Map<String, Object>> genericTypes,
                                      AnnotationMetadata annotationMetadata) {
        visitPreDestroyMethodDefinition();
        visitMethodInjectionPointInternal(
                declaringType,
                requiresReflection,
                returnType, methodName,
                argumentTypes,
                argumentAnnotationMetadata,
                genericTypes,
                annotationMetadata,
                constructorVisitor,
                preDestroyMethodVisitor,
                preDestroyInstanceIndex,
                ADD_PRE_DESTROY_METHOD);
    }

    @Override
    public void visitMethodInjectionPoint(Object declaringType,
                                          boolean requiresReflection,
                                          Object returnType,
                                          String methodName,
                                          Map<String, Object> argumentTypes,
                                          Map<String, AnnotationMetadata> argumentAnnotationMetadata,
                                          Map<String, Map<String, Object>> genericTypes,
                                          AnnotationMetadata annotationMetadata) {
        GeneratorAdapter constructorVisitor = this.constructorVisitor;
        GeneratorAdapter injectMethodVisitor = this.injectMethodVisitor;
        int injectInstanceIndex = this.injectInstanceIndex;

        visitMethodInjectionPointInternal(
                declaringType,
                requiresReflection,
                returnType,
                methodName,
                argumentTypes,
                argumentAnnotationMetadata,
                genericTypes,
                annotationMetadata,
                constructorVisitor,
                injectMethodVisitor,
                injectInstanceIndex,
                ADD_METHOD_INJECTION_POINT_METHOD);
    }

    @Override
    public ExecutableMethodWriter visitExecutableMethod(Object declaringType,
                                                        Object returnType,
                                                        Object genericReturnType,
                                                        Map<String, Object> returnTypeGenericTypes,
                                                        String methodName,
                                                        Map<String, Object> argumentTypes,
                                                        Map<String, AnnotationMetadata> argumentAnnotationMetadata,
                                                        Map<String, Map<String, Object>> genericTypes,
                                                        AnnotationMetadata annotationMetadata) {

        String methodProxyShortName = "$exec" + ++methodExecutorIndex;
        String methodExecutorClassName = beanDefinitionName + "$" + methodProxyShortName;
        ExecutableMethodWriter executableMethodWriter = new ExecutableMethodWriter(
                beanFullClassName,
                methodExecutorClassName,
                methodProxyShortName,
                isInterface,
                annotationMetadata);
        // TODO: fix so that exec classes are static inner
//        executableMethodWriter.makeStaticInner(beanDefinitionInternalName, (ClassWriter) classWriter);
        executableMethodWriter.visitMethod(
                declaringType,
                returnType,
                genericReturnType,
                returnTypeGenericTypes,
                methodName,
                argumentTypes,
                argumentAnnotationMetadata,
                genericTypes
        );

        methodExecutors.put(methodExecutorClassName, executableMethodWriter);

        if (constructorVisitor == null) {
            throw new IllegalStateException("Method visitBeanDefinitionConstructor(..) should be called first!");
        }

        constructorVisitor.visitVarInsn(ALOAD, 0);
        String methodExecutorInternalName = executableMethodWriter.getInternalName();
        constructorVisitor.visitTypeInsn(NEW, methodExecutorInternalName);
        constructorVisitor.visitInsn(DUP);
        constructorVisitor.visitMethodInsn(INVOKESPECIAL,
                methodExecutorInternalName,
                CONSTRUCTOR_NAME,
                DESCRIPTOR_DEFAULT_CONSTRUCTOR,
                false);

        pushInvokeMethodOnSuperClass(constructorVisitor, ADD_EXECUTABLE_METHOD);
        return executableMethodWriter;
    }

    @Override
    public String toString() {
        return "BeanDefinitionWriter{" +
                "beanFullClassName='" + beanFullClassName + '\'' +
                '}';
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public String getBeanSimpleName() {
        return beanSimpleClassName;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return this.annotationMetadata;
    }

    @Override
    public void visitConfigBuilderField(Object type, String field, AnnotationMetadata annotationMetadata, ConfigurationMetadataBuilder metadataBuilder) {
        String factoryMethod = annotationMetadata
                .getValue(
                        ConfigurationBuilder.class,
                        "factoryMethod",
                        String.class)
                .orElse(null);

        if (StringUtils.isNotEmpty(factoryMethod)) {
            Type builderType = getTypeReference(type);

            injectMethodVisitor.visitVarInsn(ALOAD, injectInstanceIndex);
            injectMethodVisitor.invokeStatic(
                    builderType,
                    org.objectweb.asm.commons.Method.getMethod(
                            builderType.getClassName() + " " + factoryMethod + "()"
                    )
            );

            injectMethodVisitor.putField(beanType, field, builderType);
        }

        this.currentConfigBuilderState = new ConfigBuilderState(
                type,
                field,
                false,
                annotationMetadata,
                metadataBuilder);
    }

    @Override
    public void visitConfigBuilderMethod(Object type, String methodName, AnnotationMetadata annotationMetadata, ConfigurationMetadataBuilder metadataBuilder) {
        this.currentConfigBuilderState = new ConfigBuilderState(type, methodName, true, annotationMetadata, metadataBuilder);
    }

    @Override
    public void visitConfigBuilderDurationMethod(String prefix, String configurationPrefix, Object returnType, String methodName) {
        visitConfigBuilderMethodInternal(
                prefix,
                configurationPrefix,
                returnType,
                methodName,
                Duration.class,
                Collections.emptyMap(),
                true
        );
    }

    @Override
    public void visitConfigBuilderMethod(
            String prefix,
            String configurationPrefix,
            Object returnType,
            String methodName,
            Object paramType,
            Map<String, Object> generics) {
        visitConfigBuilderMethodInternal(prefix, configurationPrefix, returnType, methodName, paramType, generics, false);
    }



    @Override
    public void visitConfigBuilderEnd() {
        currentConfigBuilderState = null;
    }

    @Override
    public void setRequiresMethodProcessing(boolean shouldPreProcess) {
        this.preprocessMethods = shouldPreProcess;
    }

    @Override
    public boolean requiresMethodProcessing() {
        return this.preprocessMethods;
    }

    /**
     * Visits a field injection point
     *
     * @param qualifierType      The qualifier type. Either a Class or a string representing the name of the type
     * @param requiresReflection Whether accessing the field requires reflection
     * @param fieldType          The type of the field
     * @param fieldName          The name of the field
     */
    public void visitFieldInjectionPoint(Object qualifierType,
                                         boolean requiresReflection,
                                         Object fieldType,
                                         String fieldName) {
        visitFieldInjectionPoint(beanFullClassName, qualifierType, requiresReflection, fieldType, fieldName);
    }

    /**
     * Visits a field injection point
     *
     * @param requiresReflection Whether accessing the field requires reflection
     * @param fieldType          The type of the field
     * @param fieldName          The name of the field
     */
    public void visitFieldInjectionPoint(boolean requiresReflection,
                                         Object fieldType,
                                         String fieldName) {
        visitFieldInjectionPoint(beanFullClassName, null, requiresReflection, fieldType, fieldName);
    }

    @Override
    public void visitFieldInjectionPoint(Object declaringType,
                                         Object qualifierType,
                                         boolean requiresReflection,
                                         Object fieldType,
                                         String fieldName) {
        // Implementation notes.
        // This method modifies the constructor adding addInjectPoint calls for each field that is annotated with @Inject
        // The constructor is a zero args constructor therefore there are no other local variables and "this" is stored in the 0 index.
        // The "currentFieldIndex" variable is used as a reference point for both the position of the local variable and also
        // for later on within the "build" method to in order to call "getBeanForField" with the appropriate index
        visitFieldInjectionPointInternal(declaringType, qualifierType, requiresReflection, fieldType, fieldName, GET_BEAN_FOR_FIELD, false);
    }

    @Override
    public void visitFieldValue(Object declaringType,
                                Object qualifierType,
                                boolean requiresReflection,
                                Object fieldType,
                                String fieldName,
                                boolean isOptional) {
        // Implementation notes.
        // This method modifies the constructor adding addInjectPoint calls for each field that is annotated with @Inject
        // The constructor is a zero args constructor therefore there are no other local variables and "this" is stored in the 0 index.
        // The "currentFieldIndex" variable is used as a reference point for both the position of the local variable and also
        // for later on within the "build" method to in order to call "getBeanForField" with the appropriate index
        visitFieldInjectionPointInternal(declaringType, qualifierType, requiresReflection, fieldType, fieldName, GET_VALUE_FOR_FIELD, isOptional);
    }

    private void visitConfigBuilderMethodInternal(
            String prefix,
            String configurationPrefix,
            Object returnType,
            String methodName,
            Object paramType,
            Map<String, Object> generics,
            boolean isDurationWithTimeUnit) {
        if (currentConfigBuilderState != null) {
            Type builderType = currentConfigBuilderState.getType();
            String builderName = currentConfigBuilderState.getName();
            boolean isResolveBuilderViaMethodCall = currentConfigBuilderState.isMethod();
            ConfigurationMetadataBuilder<?> metadataBuilder = currentConfigBuilderState.getMetadataBuilder();
            GeneratorAdapter injectMethodVisitor = this.injectMethodVisitor;

            String propertyName = NameUtils.decapitalize(methodName.substring(prefix.length()));
            // at some point we may want to support nested builders, hence the arrays and property path resolution
            String[] propertyPath;
            if (StringUtils.isNotEmpty(configurationPrefix)) {
                propertyPath = new String[]{configurationPrefix, propertyName};
            } else {
                propertyPath = new String[]{propertyName};
            }
            boolean zeroArgs = paramType == null;
            Type paramTypeRef = !zeroArgs ? getTypeReference(paramType) : null;

            // visit the property metadata
            metadataBuilder.visitProperty(
                    paramTypeRef != null ? paramTypeRef.getClassName() : Boolean.class.getName(),
                    Arrays.stream(propertyPath).collect(Collectors.joining(".")),
                    null,
                    null
            );

            // Optional optional = AbstractBeanDefinition.getValueForPath(...)
            pushGetValueForPathCall(injectMethodVisitor, paramType, propertyName, propertyPath, zeroArgs, generics);

            Label ifEnd = new Label();
            // if(optional.isPresent())
            injectMethodVisitor.invokeVirtual(Type.getType(Optional.class), org.objectweb.asm.commons.Method.getMethod(
                    ReflectionUtils.getRequiredMethod(Optional.class, "isPresent")
            ));
            injectMethodVisitor.push(false);
            injectMethodVisitor.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, ifEnd);
            injectMethodVisitor.visitLabel(new Label());

            injectMethodVisitor.visitVarInsn(ALOAD, injectInstanceIndex);

            if (isResolveBuilderViaMethodCall) {
                String desc = builderType.getClassName() + " " + builderName + "()";
                injectMethodVisitor.invokeVirtual(beanType, org.objectweb.asm.commons.Method.getMethod(desc));
            } else {
                injectMethodVisitor.getField(beanType, builderName, builderType);
            }

            String methodDescriptor;
            if (zeroArgs) {
                methodDescriptor = getMethodDescriptor(returnType, Collections.emptyList());
            } else if (isDurationWithTimeUnit) {
                methodDescriptor = getMethodDescriptor(returnType, Arrays.asList(long.class, TimeUnit.class));
            } else {
                methodDescriptor = getMethodDescriptor(returnType, Collections.singleton(paramType));
            }
            injectMethodVisitor.visitVarInsn(ALOAD, optionalInstanceIndex);
            // get the value: optional.get()
            injectMethodVisitor.invokeVirtual(Type.getType(Optional.class), org.objectweb.asm.commons.Method.getMethod(
                    ReflectionUtils.getRequiredMethod(Optional.class, "get")
            ));
            pushCastToType(injectMethodVisitor, !zeroArgs ? paramType : boolean.class);

            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label exceptionHandler = new Label();

            injectMethodVisitor.visitLabel(tryStart);
            if (zeroArgs) {
                Label zeroArgsEnd = new Label();
                injectMethodVisitor.push(false);
                injectMethodVisitor.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, zeroArgsEnd);
                injectMethodVisitor.visitLabel(new Label());
                injectMethodVisitor.invokeVirtual(
                        builderType,
                        new org.objectweb.asm.commons.Method(methodName, methodDescriptor)
                );

                injectMethodVisitor.visitLabel(zeroArgsEnd);
            } else if (isDurationWithTimeUnit) {
                injectMethodVisitor.invokeVirtual(Type.getType(Duration.class), org.objectweb.asm.commons.Method.getMethod(
                        ReflectionUtils.getRequiredMethod(Duration.class, "toMillis")
                ));
                Type tu = Type.getType(TimeUnit.class);
                injectMethodVisitor.getStatic(tu, "MILLISECONDS", tu);
                injectMethodVisitor.invokeVirtual(
                        builderType,
                        new org.objectweb.asm.commons.Method(methodName, methodDescriptor)
                );

            } else {
                injectMethodVisitor.invokeVirtual(
                        builderType,
                        new org.objectweb.asm.commons.Method(methodName, methodDescriptor)
                );
            }
            if (returnType != void.class) {
                injectMethodVisitor.pop();
            }
            injectMethodVisitor.visitJumpInsn(GOTO, tryEnd);
            injectMethodVisitor.visitLabel(exceptionHandler);
            injectMethodVisitor.pop();
            injectMethodVisitor.loadThis();
            injectMethodVisitor.push(builderType);
            injectMethodVisitor.push(methodName);
            injectMethodVisitor.push(propertyName);
            pushInvokeMethodOnSuperClass(injectMethodVisitor, ReflectionUtils.getRequiredInternalMethod(
                    AbstractBeanDefinition.class,
                    "warnMissingProperty",
                    Class.class,
                    String.class,
                    String.class
            ));

            injectMethodVisitor.visitLabel(tryEnd);
            injectMethodVisitor.visitTryCatchBlock(tryStart, tryEnd, exceptionHandler, Type.getInternalName(NoSuchMethodError.class));

            injectMethodVisitor.visitLabel(ifEnd);
        }
    }

    private void pushGetValueForPathCall(GeneratorAdapter injectMethodVisitor, Object propertyType, String propertyName, String[] propertyPath, boolean zeroArgs, Map<String, Object> generics) {
        injectMethodVisitor.loadThis();
        injectMethodVisitor.loadArg(0); // the resolution context
        injectMethodVisitor.loadArg(1); // the bean context
        if (zeroArgs) {
            // if the parameter type is null this is a zero args method that expects a boolean flag
            buildArgument(
                    injectMethodVisitor,
                    propertyName,
                    Boolean.class
            );
        } else {
            buildArgumentWithGenerics(
                    injectMethodVisitor,
                    propertyName,
                    Collections.singletonMap(propertyType, generics)
            );
        }

        int propertyPathLength = propertyPath.length;
        pushNewArray(injectMethodVisitor, String.class, propertyPathLength);

        for (int i = 0; i < propertyPathLength; i++) {
            pushStoreStringInArray(injectMethodVisitor, i, propertyPathLength, propertyPath[i]);
        }
        // Optional optional = AbstractBeanDefinition.getValueForPath(...)
        injectMethodVisitor.invokeVirtual(beanDefinitionType, org.objectweb.asm.commons.Method.getMethod(GET_VALUE_FOR_PATH));
        injectMethodVisitor.visitVarInsn(ASTORE, optionalInstanceIndex);
        injectMethodVisitor.visitVarInsn(ALOAD, optionalInstanceIndex);
    }

    private void buildFactoryMethodClassConstructor(
            Object factoryClass,
            String methodName,
            Map<String, Object> argumentTypes,
            Map<String, AnnotationMetadata> argumentAnnotationMetadata,
            Map<String, Map<String, Object>> genericTypes) {
        Type factoryTypeRef = getTypeReference(factoryClass);
        this.constructorVisitor = buildProtectedConstructor(BEAN_DEFINITION_METHOD_CONSTRUCTOR);

        GeneratorAdapter defaultConstructor = new GeneratorAdapter(
                startConstructor(classWriter),
                ACC_PUBLIC,
                CONSTRUCTOR_NAME,
                DESCRIPTOR_DEFAULT_CONSTRUCTOR
        );

        // ALOAD 0
        defaultConstructor.loadThis();

        // First constructor argument: The factory method
        boolean hasArgs = !argumentTypes.isEmpty();
        Collection<Object> argumentTypeClasses = hasArgs ? argumentTypes.values() : Collections.emptyList();
        // load 'this'
        defaultConstructor.loadThis();

        pushGetMethodFromTypeCall(defaultConstructor, factoryTypeRef, methodName, argumentTypeClasses);

        if (hasArgs) {
            pushBuildArgumentsForMethod(
                    defaultConstructor,
                    generatorAdapter -> pushGetMethodFromTypeCall(generatorAdapter, factoryTypeRef, methodName, argumentTypeClasses),
                    argumentTypes,
                    argumentAnnotationMetadata,
                    genericTypes
            );

            // now invoke super(..) if no arg constructor
        } else {
            defaultConstructor.visitInsn(ACONST_NULL);
        }
        defaultConstructor.invokeConstructor(
                beanDefinitionType,
                BEAN_DEFINITION_METHOD_CONSTRUCTOR
        );

        defaultConstructor.visitInsn(RETURN);
        defaultConstructor.visitMaxs(DEFAULT_MAX_STACK, 1);
        defaultConstructor.visitEnd();
    }

    private void visitFieldInjectionPointInternal(Object declaringType, Object qualifierType, boolean requiresReflection, Object fieldType, String fieldName, Method methodToInvoke, boolean isValueOptional) {
        // ready this
        GeneratorAdapter constructorVisitor = this.constructorVisitor;

        constructorVisitor.loadThis();

        // lookup the Field instance from the declaring type
        Type declaringTypeRef = getTypeReference(declaringType);
        int fieldVarIndex = pushGetFieldFromTypeLocalVariable(constructorVisitor, declaringTypeRef, fieldName);

        // first argument to the method is the Field reference
        // load the first argument. The field.
        constructorVisitor.visitVarInsn(ALOAD, fieldVarIndex);

        // second argument is the annotation or null
        // pass the qualifier type if present
        if (qualifierType != null) {

            constructorVisitor.visitVarInsn(ALOAD, fieldVarIndex);
            pushGetAnnotationForField(constructorVisitor, getTypeReference(qualifierType));
        } else {
            constructorVisitor.visitInsn(ACONST_NULL);
        }

        // third argument is whether it requires reflection
        constructorVisitor.push(requiresReflection);

        // invoke addInjectionPoint method
        pushInvokeMethodOnSuperClass(constructorVisitor, ADD_FIELD_INJECTION_POINT_METHOD);

        GeneratorAdapter injectMethodVisitor = this.injectMethodVisitor;

        Label falseCondition = null;
        if (isValueOptional) {
            Label trueCondition = new Label();
            falseCondition = new Label();
            injectMethodVisitor.loadThis();
            // 1st argument load BeanResolutionContext
            injectMethodVisitor.loadArg(0);
            // 2nd argument load BeanContext
            injectMethodVisitor.loadArg(1);
            // 3rd argument the field index
            injectMethodVisitor.push(currentFieldIndex);

            // invoke method containsValueForMethodArgument
            injectMethodVisitor.invokeVirtual(beanDefinitionType, org.objectweb.asm.commons.Method.getMethod(CONTAINS_VALUE_FOR_FIELD));
            injectMethodVisitor.push(false);

            injectMethodVisitor.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, falseCondition);
            injectMethodVisitor.visitLabel(trueCondition);
        }

        if (!requiresReflection) {
            // if reflection is not required then set the field automatically within the body of the "injectBean" method

            injectMethodVisitor.visitVarInsn(ALOAD, injectInstanceIndex);
            // first get the value of the field by calling AbstractBeanDefinition.getBeanForField(..)
            // load 'this'
            injectMethodVisitor.loadThis();
            // 1st argument load BeanResolutionContext
            injectMethodVisitor.visitVarInsn(ALOAD, 1);
            // 2nd argument load BeanContext
            injectMethodVisitor.visitVarInsn(ALOAD, 2);
            // 3rd argument the field index
            injectMethodVisitor.push(currentFieldIndex);
            // invoke getBeanForField
            pushInvokeMethodOnSuperClass(injectMethodVisitor, methodToInvoke);
            // cast the return value to the correct type
            pushCastToType(injectMethodVisitor, fieldType);

            injectMethodVisitor.visitFieldInsn(PUTFIELD, declaringTypeRef.getInternalName(), fieldName, getTypeDescriptor(fieldType));
        } else {
            // if reflection is required at reflective call
            pushInjectMethodForIndex(injectMethodVisitor, injectInstanceIndex, currentFieldIndex, "injectBeanField");
        }
        if (falseCondition != null) {
            injectMethodVisitor.visitLabel(falseCondition);
        }
        currentFieldIndex++;
    }

    private int pushGetFieldFromTypeLocalVariable(MethodVisitor methodVisitor, Type declaringType, String fieldName) {
        methodVisitor.visitLdcInsn(declaringType);
        // and the field name
        methodVisitor.visitLdcInsn(fieldName);
        pushInvokeMethodOnClass(methodVisitor, "getDeclaredField", String.class);

        // store the field within using the field index. A pre-increment is used because 0 contains "this"
        return pushNewConstructorLocalVariable();
    }

    private int pushGetMethodFromTypeCallLocalVariable(GeneratorAdapter methodVisitor, Type declaringType, String methodName, Collection<Object> argumentTypes) {
        pushGetMethodFromTypeCall(methodVisitor, declaringType, methodName, argumentTypes);
        return pushNewConstructorLocalVariable();
    }

    private void addInjectionPointForSetterInternal(
            AnnotationMetadata fieldAnnotationMetadata,
            boolean requiresReflection,
            Object fieldType,
            String fieldName,
            String setterName,
            Map<String, Object> genericTypes,
            Type declaringTypeRef) {
        GeneratorAdapter generatorAdapter = this.constructorVisitor;
        int fieldVarIndex = pushGetFieldFromTypeLocalVariable(generatorAdapter, declaringTypeRef, fieldName);
        List<Object> argumentTypes = Collections.singletonList(fieldType);
        int currentMethodVar = pushGetMethodFromTypeCallLocalVariable(generatorAdapter, declaringTypeRef, setterName, argumentTypes);
        generatorAdapter.visitVarInsn(ALOAD, currentMethodVar);

        // load this
        generatorAdapter.visitVarInsn(ALOAD, 0);
        // 1st argument: the field
        generatorAdapter.visitVarInsn(ALOAD, fieldVarIndex);

        // 2nd argument: the method
        generatorAdapter.visitVarInsn(ALOAD, currentMethodVar);

        // 1st argument: the constructor
        generatorAdapter.visitVarInsn(ALOAD, fieldVarIndex);
        // 2nd argument: The argument name
        generatorAdapter.push(fieldName);
        // 3rd argument:  The qualifier type
        if (fieldAnnotationMetadata != null) {
            // TODO: replace write metadata
            Optional<String> qualifier = fieldAnnotationMetadata.getAnnotationNameByStereotype(Qualifier.class);
            if (qualifier.isPresent()) {
                generatorAdapter.push(getTypeReference(qualifier.get()));
            } else {
                generatorAdapter.visitInsn(ACONST_NULL);
            }
        } else {
            generatorAdapter.visitInsn(ACONST_NULL);
        }

        // 5h argument: The generic types
        if (genericTypes != null) {
            buildTypeArguments(generatorAdapter, genericTypes);
        } else {
            generatorAdapter.visitInsn(ACONST_NULL);
        }

        // Argument.create( .. )
        invokeInterfaceStaticMethod(
                generatorAdapter,
                Argument.class,
                METHOD_CREATE_ARGUMENT_FIELD
        );

        // 4th argument: requires reflection
        generatorAdapter.visitInsn(requiresReflection ? ICONST_1 : ICONST_0);

        // now invoke the addInjectionPoint method
        pushInvokeMethodOnSuperClass(generatorAdapter, ADD_SETTER_INJECTION_POINT_METHOD);
    }

    private void visitMethodInjectionPointInternal(Object declaringType,
                                                   boolean requiresReflection,
                                                   Object returnType,
                                                   String methodName,
                                                   Map<String, Object> argumentTypes,
                                                   Map<String, AnnotationMetadata> argumentMetadata,
                                                   Map<String, Map<String, Object>> genericTypes,
                                                   AnnotationMetadata annotationMetadata, GeneratorAdapter constructorVisitor,
                                                   GeneratorAdapter injectMethodVisitor,
                                                   int injectInstanceIndex,
                                                   Method addMethodInjectionPointMethod) {
        boolean hasArguments = argumentTypes != null && !argumentTypes.isEmpty();
        int argCount = hasArguments ? argumentTypes.size() : 0;
        Type declaringTypeRef = getTypeReference(declaringType);

        // load 'this'
        constructorVisitor.visitVarInsn(ALOAD, 0);


        // 1st argument: The declaring type
        constructorVisitor.push(declaringTypeRef);

        // 2nd argument: The method name
        constructorVisitor.push(methodName);

        // 3rd argument: the argument types
        if (hasArguments) {
            pushBuildArgumentsForMethod(
                    constructorVisitor,
                    argumentTypes,
                    argumentMetadata,
                    genericTypes
            );
        } else {
            constructorVisitor.visitInsn(ACONST_NULL);
        }

        // 4th argument: the annotation metadata
        if (annotationMetadata == AnnotationMetadata.EMPTY_METADATA) {
            constructorVisitor.visitInsn(ACONST_NULL);
        } else {
            AnnotationMetadataWriter.instantiateNewMetadata(constructorVisitor, (DefaultAnnotationMetadata) annotationMetadata);
        }
        // 5th  argument to addInjectionPoint: do we need reflection?
        constructorVisitor.visitInsn(requiresReflection ? ICONST_1 : ICONST_0);
        Collection<Object> argumentTypeClasses = hasArguments ? argumentTypes.values() : Collections.emptyList();

        // invoke add injection point method
        pushInvokeMethodOnSuperClass(constructorVisitor, addMethodInjectionPointMethod);

        if (!requiresReflection) {
            // if the method doesn't require reflection then invoke it directly

            // invoke the method on this injected instance
            injectMethodVisitor.visitVarInsn(ALOAD, injectInstanceIndex);

            String methodDescriptor;
            if (hasArguments) {
                methodDescriptor = getMethodDescriptor(returnType, argumentTypeClasses);
                Iterator<Map.Entry<String, Object>> argIterator = argumentTypes.entrySet().iterator();
                for (int i = 0; i < argCount; i++) {
                    Map.Entry<String, Object> entry = argIterator.next();
                    AnnotationMetadata argMetadata = argumentMetadata.get(entry.getKey());

                    // first get the value of the field by calling AbstractBeanDefinition.getBeanForMethod(..)
                    // load 'this'
                    injectMethodVisitor.visitVarInsn(ALOAD, 0);
                    // 1st argument load BeanResolutionContext
                    injectMethodVisitor.visitVarInsn(ALOAD, 1);
                    // 2nd argument load BeanContext
                    injectMethodVisitor.visitVarInsn(ALOAD, 2);
                    // 3rd argument the method index
                    injectMethodVisitor.push(currentMethodIndex);
                    // 4th argument the argument index
                    injectMethodVisitor.push(i);
                    // invoke getBeanForField

                    Method methodToInvoke = argMetadata.hasDeclaredStereotype(Value.class) ? GET_VALUE_FOR_METHOD_ARGUMENT : GET_BEAN_FOR_METHOD_ARGUMENT;
                    pushInvokeMethodOnSuperClass(injectMethodVisitor, methodToInvoke);
                    // cast the return value to the correct type
                    pushCastToType(injectMethodVisitor, entry.getValue());
                }
            } else {
                methodDescriptor = getMethodDescriptor(returnType, Collections.emptyList());
            }
            injectMethodVisitor.visitMethodInsn(isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL,
                    declaringTypeRef.getInternalName(), methodName,
                    methodDescriptor, isInterface);
        } else {
            // otherwise use injectBeanMethod instead which triggers reflective injection
            pushInjectMethodForIndex(injectMethodVisitor, injectInstanceIndex, currentMethodIndex, "injectBeanMethod");
        }

        // increment the method index
        currentMethodIndex++;
    }

    static void pushBuildArgumentsForMethod(
            GeneratorAdapter generatorAdapter,
            Consumer<GeneratorAdapter> methodSupplier,
            Map<String, Object> argumentTypes,
            Map<String, AnnotationMetadata> argumentAnnotationMetadata,
            Map<String, Map<String, Object>> genericTypes) {
        int len = argumentTypes.size();
        pushNewArray(generatorAdapter, Argument.class, len);
        int i = 0;
        for (Map.Entry<String, Object> entry : argumentTypes.entrySet()) {
            // the array index position
            generatorAdapter.push(i);

            String argumentName = entry.getKey();

            // 1st argument: resolve the method
            methodSupplier.accept(generatorAdapter);
            // 2nd argument: The argument name
            generatorAdapter.push(argumentName);
            // 3rd argument: The index
            generatorAdapter.push(i);

            // 4th argument: The annotation metadata
            // TODO: populate real metadata
            AnnotationMetadata annotationMetadata = argumentAnnotationMetadata.get(argumentName);
            if (annotationMetadata != null) {
                Optional<String> qualifier = annotationMetadata.getAnnotationNameByStereotype(Qualifier.class);
                if (qualifier.isPresent()) {
                    generatorAdapter.push(getTypeReference(qualifier.get()));
                } else {
                    generatorAdapter.visitInsn(ACONST_NULL);
                }
            } else {
                generatorAdapter.visitInsn(ACONST_NULL);
            }
//            if(annotationMetadata == null || annotationMetadata == AnnotationMetadata.EMPTY_METADATA) {
//                generatorAdapter.visitInsn(ACONST_NULL);
//            }
//            else {
//                AnnotationMetadataWriter.instantiateNewMetadata(generatorAdapter, (DefaultAnnotationMetadata) annotationMetadata);
//            }

            // 5h argument: The generic types
            if (genericTypes != null && genericTypes.containsKey(argumentName)) {
                Map<String, Object> types = genericTypes.get(argumentName);
                buildTypeArguments(generatorAdapter, types);
            } else {
                generatorAdapter.visitInsn(ACONST_NULL);
            }

            // Argument.create( .. )
            invokeInterfaceStaticMethod(
                    generatorAdapter,
                    Argument.class,
                    METHOD_CREATE_ARGUMENT_METHOD
            );
            // store the type reference
            generatorAdapter.visitInsn(AASTORE);
            // if we are not at the end of the array duplicate array onto the stack
            if (i != (len - 1)) {
                generatorAdapter.visitInsn(DUP);
            }
            i++;
        }
    }

    static void pushBuildArgumentsForMethod(
            GeneratorAdapter generatorAdapter,
            Map<String, Object> argumentTypes,
            Map<String, AnnotationMetadata> argumentAnnotationMetadata,
            Map<String, Map<String, Object>> genericTypes) {
        int len = argumentTypes.size();
        pushNewArray(generatorAdapter, Argument.class, len);
        int i = 0;
        for (Map.Entry<String, Object> entry : argumentTypes.entrySet()) {
            // the array index position
            generatorAdapter.push(i);

            String argumentName = entry.getKey();
            Type argumentType = getTypeReference(entry.getValue());

            // 1st argument: The type
            generatorAdapter.push(argumentType);

            // 2nd argument: The argument name
            generatorAdapter.push(argumentName);

            // 3rd argument: The annotation metadata
            AnnotationMetadata annotationMetadata = argumentAnnotationMetadata.get(argumentName);
            if (annotationMetadata == null || annotationMetadata == AnnotationMetadata.EMPTY_METADATA) {
                generatorAdapter.visitInsn(ACONST_NULL);
            } else {
                AnnotationMetadataWriter.instantiateNewMetadata(generatorAdapter, (DefaultAnnotationMetadata) annotationMetadata);
            }


            // 4th argument: The generic types
            if (genericTypes != null && genericTypes.containsKey(argumentName)) {
                Map<String, Object> types = genericTypes.get(argumentName);
                buildTypeArguments(generatorAdapter, types);
            } else {
                generatorAdapter.visitInsn(ACONST_NULL);
            }

            // Argument.create( .. )
            invokeInterfaceStaticMethod(
                    generatorAdapter,
                    Argument.class,
                    METHOD_CREATE_ARGUMENT_WITH_ANNOTATION_METADATA_GENERICS
            );
            // store the type reference
            generatorAdapter.visitInsn(AASTORE);
            // if we are not at the end of the array duplicate array onto the stack
            if (i != (len - 1)) {
                generatorAdapter.visitInsn(DUP);
            }
            i++;
        }
    }

    private void pushInvokeMethodOnSuperClass(MethodVisitor constructorVisitor, Method methodToInvoke) {
        constructorVisitor.visitMethodInsn(INVOKESPECIAL,
                isSuperFactory ? TYPE_ABSTRACT_BEAN_DEFINITION.getInternalName() : superType.getInternalName(),
                methodToInvoke.getName(),
                Type.getMethodDescriptor(methodToInvoke),
                false);
    }

    private void resolveBeanOrValueForSetter(Type declaringTypeRef, String setterName, Object fieldType, Method resolveMethod, boolean isValueOptional) {
        GeneratorAdapter injectVisitor = this.injectMethodVisitor;

        Label falseCondition = null;
        if (isValueOptional) {
            Label trueCondition = new Label();
            falseCondition = new Label();
            injectVisitor.loadThis();
            // 1st argument load BeanResolutionContext
            injectVisitor.loadArg(0);
            // 2nd argument load BeanContext
            injectVisitor.loadArg(1);
            // 3rd argument the field index
            injectVisitor.push(currentMethodIndex);
            // 4th argument the argument index
            injectVisitor.push(0);

            // invoke method containsValueForMethodArgument
            injectVisitor.invokeVirtual(beanDefinitionType, org.objectweb.asm.commons.Method.getMethod(CONTAINS_VALUE_FOR_METHOD_ARGUMENT));
            injectVisitor.push(false);

            injectVisitor.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, falseCondition);
            injectVisitor.visitLabel(trueCondition);
        }
        // invoke the method on this injected instance
        injectVisitor.visitVarInsn(ALOAD, injectInstanceIndex);
        String methodDescriptor = getMethodDescriptor("void", Collections.singletonList(fieldType));
        // first get the value of the field by calling AbstractBeanDefinition.getBeanForField(..)
        // load 'this'
        injectMethodVisitor.visitVarInsn(ALOAD, 0);
        // 1st argument load BeanResolutionContext
        injectMethodVisitor.visitVarInsn(ALOAD, 1);
        // 2nd argument load BeanContext
        injectMethodVisitor.visitVarInsn(ALOAD, 2);
        // 3rd argument the field index
        injectMethodVisitor.push(currentMethodIndex);
        // 4th argument the argument index
        // 5th argument is the default value
        injectVisitor.push(0);
        // invoke getBeanForField
        pushInvokeMethodOnSuperClass(injectVisitor, resolveMethod);
        // cast the return value to the correct type
        pushCastToType(injectVisitor, fieldType);
        injectVisitor.visitMethodInsn(INVOKEVIRTUAL,
                declaringTypeRef.getInternalName(), setterName,
                methodDescriptor, false);
        if (falseCondition != null) {
            injectVisitor.visitLabel(falseCondition);
        }
    }

    static void pushInvokeMethodOnClass(MethodVisitor methodVisitor, String classMethodName, Class... classMethodArgs) {
        Method method = ReflectionUtils
                .getDeclaredMethod(Class.class, classMethodName, classMethodArgs)
                .orElseThrow(() -> new IllegalStateException("Class." + classMethodName + "(..) method not found"));

        methodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                Type.getInternalName(Class.class),
                classMethodName,
                Type.getMethodDescriptor(method),
                false);
    }

    private void visitInjectMethodDefinition() {
        if (injectMethodVisitor == null) {
            String desc = getMethodDescriptor(Object.class.getName(), BeanResolutionContext.class.getName(), BeanContext.class.getName(), Object.class.getName());
            injectMethodVisitor = new GeneratorAdapter(classWriter.visitMethod(
                    ACC_PROTECTED,
                    "injectBean",
                    desc,
                    null,
                    null), ACC_PROTECTED, "injectBean", desc);

            GeneratorAdapter injectMethodVisitor = this.injectMethodVisitor;
            if (isConfigurationProperties) {
                injectMethodVisitor.loadThis();
                injectMethodVisitor.loadArg(0); // the resolution context
                injectMethodVisitor.loadArg(1); // the bean context
                // invoke AbstractBeanDefinition.containsProperties(..)
                injectMethodVisitor.invokeVirtual(beanDefinitionType, org.objectweb.asm.commons.Method.getMethod(CONTAINS_PROPERTIES_METHOD));
                injectMethodVisitor.push(false);
                injectEnd = new Label();
                injectMethodVisitor.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, injectEnd);
                // add the true condition
                injectMethodVisitor.visitLabel(new Label());
            }
            // The object being injected is argument 3 of the inject method
            injectMethodVisitor.visitVarInsn(ALOAD, 3);
            // store it in a local variable
            injectMethodVisitor.visitTypeInsn(CHECKCAST, beanType.getInternalName());
            injectInstanceIndex = pushNewInjectLocalVariable();
            injectMethodVisitor.visitInsn(ACONST_NULL);
            optionalInstanceIndex = pushNewInjectLocalVariable();
        }
    }

    private void visitPostConstructMethodDefinition() {
        if (postConstructMethodVisitor == null) {
            interfaceTypes.add(InitializingBeanDefinition.class);

            // override the post construct method
            GeneratorAdapter postConstructMethodVisitor = newLifeCycleMethod("initialize");

            this.postConstructMethodVisitor = postConstructMethodVisitor;
            // The object being injected is argument 3 of the inject method
            postConstructMethodVisitor.visitVarInsn(ALOAD, 3);
            // store it in a local variable
            postConstructMethodVisitor.visitTypeInsn(CHECKCAST, beanType.getInternalName());
            postConstructInstanceIndex = pushNewPostConstructLocalVariable();

            invokeSuperInjectMethod(postConstructMethodVisitor, POST_CONSTRUCT_METHOD);

            pushBeanDefinitionMethodInvocation(buildMethodVisitor, "initialize");
            pushCastToType(buildMethodVisitor, beanFullClassName);
            buildMethodVisitor.visitVarInsn(ASTORE, buildInstanceIndex);
        }
    }

    private void pushInjectMethodForIndex(GeneratorAdapter methodVisitor, int instanceIndex, int injectIndex, String injectMethodName) {
        Method injectBeanMethod = ReflectionUtils.getRequiredMethod(AbstractBeanDefinition.class, injectMethodName, BeanResolutionContext.class, DefaultBeanContext.class, int.class, Object.class);
        // load 'this'
        methodVisitor.visitVarInsn(ALOAD, 0);
        // 1st argument load BeanResolutionContext
        methodVisitor.visitVarInsn(ALOAD, 1);
        // 2nd argument load BeanContext
        methodVisitor.visitVarInsn(ALOAD, 2);
        pushCastToType(methodVisitor, DefaultBeanContext.class);
        // 3rd argument the method index
        methodVisitor.push(injectIndex);
        // 4th argument: the instance being injected
        methodVisitor.visitVarInsn(ALOAD, instanceIndex);

        pushInvokeMethodOnSuperClass(methodVisitor, injectBeanMethod);
    }

    private void visitPreDestroyMethodDefinition() {
        if (preDestroyMethodVisitor == null) {
            interfaceTypes.add(DisposableBeanDefinition.class);

            // override the post construct method
            GeneratorAdapter preDestroyMethodVisitor = newLifeCycleMethod("dispose");

            this.preDestroyMethodVisitor = preDestroyMethodVisitor;
            // The object being injected is argument 3 of the inject method
            preDestroyMethodVisitor.visitVarInsn(ALOAD, 3);
            // store it in a local variable
            preDestroyMethodVisitor.visitTypeInsn(CHECKCAST, beanType.getInternalName());
            preDestroyInstanceIndex = pushNewPreDestroyLocalVariable();

            invokeSuperInjectMethod(preDestroyMethodVisitor, PRE_DESTROY_METHOD);
        }
    }

    private GeneratorAdapter newLifeCycleMethod(String methodName) {
        String desc = getMethodDescriptor(Object.class.getName(), BeanResolutionContext.class.getName(), BeanContext.class.getName(), Object.class.getName());
        return new GeneratorAdapter(classWriter.visitMethod(
                ACC_PUBLIC,
                methodName,
                desc,
                getMethodSignature(getTypeDescriptor(providedBeanClassName), getTypeDescriptor(BeanResolutionContext.class.getName()), getTypeDescriptor(BeanContext.class.getName()), getTypeDescriptor(providedBeanClassName)),
                null),
                ACC_PUBLIC,
                methodName,
                desc
        );
    }

    private void finalizeBuildMethod() {
        // if this is a provided bean then execute "get"
        if (!providedBeanClassName.equals(beanFullClassName)) {

            buildMethodVisitor.visitVarInsn(ASTORE, buildInstanceIndex);
            buildMethodVisitor.visitVarInsn(ALOAD, buildInstanceIndex);
            buildMethodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                    beanType.getInternalName(),
                    "get",
                    Type.getMethodDescriptor(Type.getType(Object.class)),
                    false);
            pushCastToType(buildMethodVisitor, providedBeanClassName);
            buildMethodVisitor.visitVarInsn(ASTORE, buildInstanceIndex);
            pushBeanDefinitionMethodInvocation(buildMethodVisitor, "injectAnother");
            pushCastToType(buildMethodVisitor, providedBeanClassName);
        }
    }

    private void finalizeInjectMethod() {
        if (injectEnd != null) {
            injectMethodVisitor.visitLabel(injectEnd);
        }

        invokeSuperInjectMethod(injectMethodVisitor, INJECT_BEAN_METHOD);
        injectMethodVisitor.visitInsn(ARETURN);
    }

    private void invokeSuperInjectMethod(MethodVisitor methodVisitor, Method methodToInvoke) {
        // load this
        methodVisitor.visitVarInsn(ALOAD, 0);
        // load BeanResolutionContext arg 1
        methodVisitor.visitVarInsn(ALOAD, 1);
        // load BeanContext arg 2
        methodVisitor.visitVarInsn(ALOAD, 2);
        pushCastToType(methodVisitor, DefaultBeanContext.class);

        // load object being inject arg 3
        methodVisitor.visitVarInsn(ALOAD, 3);
        pushInvokeMethodOnSuperClass(methodVisitor, methodToInvoke);
    }

    private void visitBuildFactoryMethodDefinition(Object factoryClass, String methodName, Map<String, Object> argumentTypes, Map<String, AnnotationMetadata> argumentAnnotationMetadata) {
        if (buildMethodVisitor == null) {
            defineBuilderMethod(argumentAnnotationMetadata);
            // load this

            GeneratorAdapter buildMethodVisitor = this.buildMethodVisitor;
            // Load the BeanContext for the method call
            buildMethodVisitor.visitVarInsn(ALOAD, 2);
            pushCastToType(buildMethodVisitor, DefaultBeanContext.class);
            // load the first argument of the method (the BeanResolutionContext) to be passed to the method
            buildMethodVisitor.visitVarInsn(ALOAD, 1);
            // second argument is the bean type
            Type factoryType = getTypeReference(factoryClass);
            buildMethodVisitor.visitLdcInsn(factoryType);
            Method getBeanMethod = ReflectionUtils.getRequiredInternalMethod(DefaultBeanContext.class, "getBean", BeanResolutionContext.class, Class.class);

            buildMethodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                    Type.getInternalName(DefaultBeanContext.class),
                    "getBean",
                    Type.getMethodDescriptor(getBeanMethod), false);

            // store a reference to the bean being built at index 3
            int factoryVar = pushNewBuildLocalVariable();
            buildMethodVisitor.visitVarInsn(ALOAD, factoryVar);
            pushCastToType(buildMethodVisitor, factoryClass);

            if (argumentTypes.isEmpty()) {
                buildMethodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                        factoryType.getInternalName(),
                        methodName,
                        Type.getMethodDescriptor(beanType), false);
            } else {
                pushContructorArguments(buildMethodVisitor, argumentTypes, argumentAnnotationMetadata);
                String methodDescriptor = getMethodDescriptor(beanFullClassName, argumentTypes.values());
                buildMethodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                        factoryType.getInternalName(),
                        methodName,
                        methodDescriptor, false);
            }
            this.buildInstanceIndex = pushNewBuildLocalVariable();
            pushBeanDefinitionMethodInvocation(buildMethodVisitor, "injectBean");
            pushCastToType(buildMethodVisitor, beanFullClassName);
            buildMethodVisitor.visitVarInsn(ASTORE, buildInstanceIndex);
            buildMethodVisitor.visitVarInsn(ALOAD, buildInstanceIndex);
        }
    }

    private void visitBuildMethodDefinition(Map<String, Object> argumentTypes, Map<String, AnnotationMetadata> qualifierTypes) {
        if (buildMethodVisitor == null) {
            defineBuilderMethod(qualifierTypes);
            // load this

            GeneratorAdapter buildMethodVisitor = this.buildMethodVisitor;
            buildMethodVisitor.visitTypeInsn(NEW, beanType.getInternalName());
            buildMethodVisitor.visitInsn(DUP);
            pushContructorArguments(buildMethodVisitor, argumentTypes, qualifierTypes);
            String constructorDescriptor = getConstructorDescriptor(argumentTypes.values());
            buildMethodVisitor.visitMethodInsn(INVOKESPECIAL, beanType.getInternalName(), "<init>", constructorDescriptor, false);
            // store a reference to the bean being built at index 3
            this.buildInstanceIndex = pushNewBuildLocalVariable();
            pushBeanDefinitionMethodInvocation(buildMethodVisitor, "injectBean");
            pushCastToType(buildMethodVisitor, beanFullClassName);
            buildMethodVisitor.visitVarInsn(ASTORE, buildInstanceIndex);
            buildMethodVisitor.visitVarInsn(ALOAD, buildInstanceIndex);
        }
    }

    private void pushContructorArguments(GeneratorAdapter buildMethodVisitor, Map<String, Object> argumentTypes, Map<String, AnnotationMetadata> argumentAnnotationMetadata) {
        int size = argumentTypes.size();
        if (size > 0) {
            Iterator<Map.Entry<String, Object>> iterator = argumentTypes.entrySet().iterator();
            for (int i = 0; i < size; i++) {
                Map.Entry<String, Object> entry = iterator.next();
                AnnotationMetadata argMetadata = argumentAnnotationMetadata.get(entry.getKey());
                if (isArgumentType(argMetadata) && argsIndex > -1) {
                    // load the args
                    buildMethodVisitor.visitVarInsn(ALOAD, argsIndex);
                    // the argument name
                    buildMethodVisitor.push(entry.getKey());
                    buildMethodVisitor.invokeInterface(Type.getType(Map.class), org.objectweb.asm.commons.Method.getMethod(ReflectionUtils.getRequiredMethod(Map.class, "get", Object.class)));
                    pushCastToType(buildMethodVisitor, entry.getValue());
                } else {

                    // Load this for method call
                    buildMethodVisitor.visitVarInsn(ALOAD, 0);

                    // load the first two arguments of the method (the BeanResolutionContext and the BeanContext) to be passed to the method
                    buildMethodVisitor.visitVarInsn(ALOAD, 1);
                    buildMethodVisitor.visitVarInsn(ALOAD, 2);
                    // pass the index of the method as the third argument
                    buildMethodVisitor.push(i);
                    // invoke the getBeanForConstructorArgument method
                    Method methodToInvoke = isValueType(argMetadata) ? GET_VALUE_FOR_CONSTRUCTOR_ARGUMENT : GET_BEAN_FOR_CONSTRUCTOR_ARGUMENT;
                    pushInvokeMethodOnSuperClass(buildMethodVisitor, methodToInvoke);
                    pushCastToType(buildMethodVisitor, entry.getValue());
                }
            }
        }
    }

    private boolean isValueType(AnnotationMetadata annotationMetadata) {
        if (annotationMetadata != null) {
            return annotationMetadata.hasDeclaredStereotype(Value.class);
        }
        return false;
    }

    private boolean isArgumentType(AnnotationMetadata annotationMetadata) {
        if (annotationMetadata != null) {
            return annotationMetadata.hasDeclaredAnnotation(Parameter.class);
        }
        return false;
    }

    private void defineBuilderMethod(Map<String, AnnotationMetadata> argumentAnnotationMetadata) {
        Optional<AnnotationMetadata> argumentQualifier = argumentAnnotationMetadata != null ? argumentAnnotationMetadata.values().stream().filter(this::isArgumentType).findFirst() : Optional.empty();
        boolean isParametrized = argumentQualifier.isPresent();
        if (isParametrized) {
            superType = TYPE_ABSTRACT_PARAMETRIZED_BEAN_DEFINITION;
            argsIndex = buildMethodLocalCount++;
        }

        String methodDescriptor;
        String methodSignature;
        if (isParametrized) {
            methodDescriptor = getMethodDescriptor(
                    Object.class.getName(),
                    BeanResolutionContext.class.getName(),
                    BeanContext.class.getName(),
                    BeanDefinition.class.getName(),
                    Map.class.getName()
            );
            methodSignature = getMethodSignature(
                    getTypeDescriptor(providedBeanClassName),
                    getTypeDescriptor(BeanResolutionContext.class.getName()),
                    getTypeDescriptor(BeanContext.class.getName()),
                    getTypeDescriptor(BeanDefinition.class.getName(),
                            providedBeanClassName),
                    getTypeDescriptor(Map.class.getName())
            );
        } else {
            methodDescriptor = getMethodDescriptor(
                    Object.class.getName(),
                    BeanResolutionContext.class.getName(),
                    BeanContext.class.getName(),
                    BeanDefinition.class.getName()
            );
            methodSignature = getMethodSignature(
                    getTypeDescriptor(providedBeanClassName),
                    getTypeDescriptor(BeanResolutionContext.class.getName()),
                    getTypeDescriptor(BeanContext.class.getName()),
                    getTypeDescriptor(BeanDefinition.class.getName(),
                            providedBeanClassName)
            );
        }

        String methodName = argumentQualifier.isPresent() ? "doBuild" : "build";
        this.buildMethodVisitor = new GeneratorAdapter(classWriter.visitMethod(
                ACC_PUBLIC,
                methodName,
                methodDescriptor,
                methodSignature,
                null), ACC_PUBLIC, methodName, methodDescriptor);
    }

    private void pushBeanDefinitionMethodInvocation(MethodVisitor buildMethodVisitor, String methodName) {
        buildMethodVisitor.visitVarInsn(ALOAD, 0);
        buildMethodVisitor.visitVarInsn(ALOAD, 1);
        buildMethodVisitor.visitVarInsn(ALOAD, 2);
        buildMethodVisitor.visitVarInsn(ALOAD, buildInstanceIndex);

        buildMethodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                beanDefinitionInternalName,
                methodName,
                Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(BeanResolutionContext.class), Type.getType(BeanContext.class), Type.getType(Object.class)),
                false);
    }

    private int pushNewBuildLocalVariable() {
        buildMethodVisitor.visitVarInsn(ASTORE, buildMethodLocalCount);
        return buildMethodLocalCount++;
    }

    private int pushNewConstructorLocalVariable() {
        constructorVisitor.visitVarInsn(ASTORE, constructorLocalVariableCount);
        return constructorLocalVariableCount++;
    }

    private int pushNewInjectLocalVariable() {
        injectMethodVisitor.visitVarInsn(ASTORE, injectMethodLocalCount);
        return injectMethodLocalCount++;
    }

    private int pushNewPostConstructLocalVariable() {
        postConstructMethodVisitor.visitVarInsn(ASTORE, postConstructMethodLocalCount);
        return postConstructMethodLocalCount++;
    }

    private int pushNewPreDestroyLocalVariable() {
        preDestroyMethodVisitor.visitVarInsn(ASTORE, preDestroyMethodLocalCount);
        return preDestroyMethodLocalCount++;
    }

    private void visitBeanDefinitionConstructorInternal(
            AnnotationMetadata constructorMetadata,
            boolean requiresReflection,
            Map<String, Object> argumentTypes,
            Map<String, AnnotationMetadata> argumentAnnotationMetadata,
            Map<String, Map<String, Object>> genericTypes) {
        if (constructorVisitor == null) {
            Optional<AnnotationMetadata> argumentQualifier = argumentAnnotationMetadata != null ? argumentAnnotationMetadata.values().stream().filter(this::isArgumentType).findFirst() : Optional.empty();
            boolean isParametrized = argumentQualifier.isPresent();
            if (isParametrized) {
                superType = TYPE_ABSTRACT_PARAMETRIZED_BEAN_DEFINITION;
            }


            // create the BeanDefinition constructor for subclasses of AbstractBeanDefinition
            org.objectweb.asm.commons.Method constructorMethod = org.objectweb.asm.commons.Method.getMethod(CONSTRUCTOR_ABSTRACT_BEAN_DEFINITION);
            GeneratorAdapter protectedConstructor = new GeneratorAdapter(
                    classWriter.visitMethod(ACC_PROTECTED, CONSTRUCTOR_NAME, constructorMethod.getDescriptor(), null, null),
                    ACC_PROTECTED,
                    CONSTRUCTOR_NAME,
                    constructorMethod.getDescriptor()
            );
            constructorVisitor = protectedConstructor;

            Type[] beanDefinitionConstructorArgumentTypes = constructorMethod.getArgumentTypes();
            protectedConstructor.loadThis();
            for (int i = 0; i < beanDefinitionConstructorArgumentTypes.length; i++) {
                protectedConstructor.loadArg(i);
            }
            protectedConstructor.invokeConstructor(isSuperFactory ? TYPE_ABSTRACT_BEAN_DEFINITION : superType, BEAN_DEFINITION_CLASS_CONSTRUCTOR);

            GeneratorAdapter defaultConstructor = startConstructor(classWriter);
            GeneratorAdapter defaultConstructorVisitor = new GeneratorAdapter(
                    defaultConstructor,
                    ACC_PUBLIC,
                    CONSTRUCTOR_NAME,
                    DESCRIPTOR_DEFAULT_CONSTRUCTOR
            );
            // ALOAD 0
            defaultConstructor.visitVarInsn(ALOAD, 0);

            // 1st argument: pass the bean definition type as the third argument to super(..)
            defaultConstructor.visitLdcInsn(this.beanType);

            // 2nd Argument: the annotation metadata
            if (constructorMetadata == null || constructorMetadata == AnnotationMetadata.EMPTY_METADATA) {
                defaultConstructor.visitInsn(ACONST_NULL);
            } else {
                AnnotationMetadataWriter.instantiateNewMetadata(defaultConstructor, (DefaultAnnotationMetadata) constructorMetadata);
            }

            // 3rd argument: Is reflection required
            defaultConstructor.visitInsn(requiresReflection ? ICONST_1 : ICONST_0);

            // 4th argument: The arguments
            if(argumentTypes == null || argumentTypes.isEmpty()) {
                defaultConstructor.visitInsn(ACONST_NULL);
            }
            else {
                pushBuildArgumentsForMethod(defaultConstructorVisitor, argumentTypes, argumentAnnotationMetadata, genericTypes);
            }

            defaultConstructorVisitor.invokeConstructor(
                    beanDefinitionType,
                    BEAN_DEFINITION_CLASS_CONSTRUCTOR
            );

            defaultConstructorVisitor.visitInsn(RETURN);
            defaultConstructorVisitor.visitMaxs(DEFAULT_MAX_STACK, 1);
            defaultConstructorVisitor.visitEnd();
        }
    }

    private void pushInitializeConstructorField(GeneratorAdapter staticInit, String constructorField, Type beanType, Map<String, Object> argumentTypes) {
        classWriter.visitField(ACC_PRIVATE_STATIC_FINAL, constructorField, TYPE_CONSTRUCTOR.getDescriptor(), null, null);

        Collection<Object> argumentClassNames = argumentTypes.values();

        pushGetConstructorForType(staticInit, beanType, argumentClassNames);

        staticInit.putStatic(
                beanDefinitionType,
                constructorField,
                TYPE_CONSTRUCTOR
        );
    }

    static void buildTypeArguments(GeneratorAdapter generatorAdapter, Map<String, Object> types) {
        if (types == null || types.isEmpty()) {
            generatorAdapter.visitInsn(ACONST_NULL);
            return;
        }
        int len = types.size();
        // Build calls to Argument.create(...)
        pushNewArray(generatorAdapter, Argument.class, len);
        int i = 0;
        for (Map.Entry<String, Object> entry : types.entrySet()) {
            // the array index
            generatorAdapter.push(i);
            String typeParameterName = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                buildArgumentWithGenerics(generatorAdapter, typeParameterName, (Map) value);
            } else {
                buildArgument(generatorAdapter, typeParameterName, value);
            }

            // store the type reference
            generatorAdapter.visitInsn(AASTORE);
            // if we are not at the end of the array duplicate array onto the stack
            if (i != (len - 1)) {
                generatorAdapter.visitInsn(DUP);
            }
            i++;
        }
    }

    private static void buildArgument(GeneratorAdapter generatorAdapter, String argumentName, Object objectType) {
        // 1st argument: the type
        generatorAdapter.push(getTypeReference(objectType));
        // 2nd argument: the name
        generatorAdapter.push(argumentName);

        // Argument.create( .. )
        invokeInterfaceStaticMethod(
                generatorAdapter,
                Argument.class,
                METHOD_CREATE_ARGUMENT_SIMPLE
        );
    }

    static void buildArgumentWithGenerics(GeneratorAdapter generatorAdapter, String argumentName, Map nestedTypeObject) {
        Map nestedTypes = null;
        Optional<Map.Entry> nestedEntry = nestedTypeObject.entrySet().stream().findFirst();
        Object objectType;
        if (nestedEntry.isPresent()) {
            Map.Entry data = nestedEntry.get();
            Object key = data.getKey();
            Object map = data.getValue();
            objectType = key;
            if (map instanceof Map) {
                nestedTypes = (Map) map;
            }
        } else {
            throw new IllegalArgumentException("Must be a map with a single key containing the argument type and a map of generics as the value");
        }

        // 1st argument: the type
        generatorAdapter.push(getTypeReference(objectType));
        // 2nd argument: the name
        generatorAdapter.push(argumentName);

        // 3rd argument: generic types
        boolean hasGenerics = nestedTypes != null && !nestedTypes.isEmpty();
        if (hasGenerics) {
            buildTypeArguments(generatorAdapter, nestedTypes);
        }

        // Argument.create( .. )
        invokeInterfaceStaticMethod(
                generatorAdapter,
                Argument.class,
                hasGenerics ? METHOD_CREATE_ARGUMENT_WITH_GENERICS : METHOD_CREATE_ARGUMENT_SIMPLE
        );
    }

    private GeneratorAdapter buildProtectedConstructor(org.objectweb.asm.commons.Method constructorType) {
        GeneratorAdapter protectedConstructor = new GeneratorAdapter(
                classWriter.visitMethod(ACC_PROTECTED, CONSTRUCTOR_NAME, constructorType.getDescriptor(), null, null),
                ACC_PROTECTED,
                CONSTRUCTOR_NAME,
                constructorType.getDescriptor()
        );

        Type[] arguments = constructorType.getArgumentTypes();
        protectedConstructor.loadThis();
        for (int i = 0; i < arguments.length; i++) {
            protectedConstructor.loadArg(i);
        }
        if (isSuperFactory) {
            protectedConstructor.invokeConstructor(TYPE_ABSTRACT_BEAN_DEFINITION, constructorType);
        } else {
            protectedConstructor.invokeConstructor(superType, constructorType);
        }
        return protectedConstructor;
    }

    static void pushGetMethodFromTypeCall(GeneratorAdapter methodVisitor, Type declaringType, String methodName, Collection<Object> argumentTypes) {
        // lookup the Method instance from the declaring type
        methodVisitor.visitLdcInsn(declaringType);
        pushMethodNameAndTypesArguments(methodVisitor, methodName, argumentTypes);


        // invoke Reflectionutils.getRequiredMethod(..)
        methodVisitor.invokeStatic(Type.getType(ReflectionUtils.class), METHOD_GET_REQUIRED_METHOD);
    }

    static void pushGetConstructorForType(GeneratorAdapter methodVisitor, Type beanType, Collection<Object> argumentClassNames) {
        methodVisitor.visitLdcInsn(beanType);

        int argCount = argumentClassNames.size();
        Object[] argumentTypeArray = argumentClassNames.toArray(new Object[argCount]);
        methodVisitor.push(argCount);
        methodVisitor.newArray(TYPE_CLASS);
        if (argCount > 0) {

            methodVisitor.dup();
            for (int i = 0; i < argCount; i++) {
                pushStoreTypeInArray(methodVisitor, i, argCount, argumentTypeArray[i]);
            }
        }

        // invoke Class.getConstructor()
        String getDeclaredConstructorMethod = "getDeclaredConstructor";
        Method getConstructorMethod = ReflectionUtils.getDeclaredMethod(Class.class, getDeclaredConstructorMethod, Class[].class)
                .orElseThrow(() ->
                        new IllegalStateException("Class.getConstructor(..) method not found")
                );
        methodVisitor.visitMethodInsn(
                INVOKEVIRTUAL,
                Type.getInternalName(Class.class),
                getDeclaredConstructorMethod,
                Type.getType(getConstructorMethod).getDescriptor(),
                false);
    }

    /**
     * Adds a method call to get the given annotation of the given type to tye stack
     *
     * @param targetClass    The target class
     * @param annotationType The annotation type
     */
    private void pushGetAnnotationForType(MethodVisitor methodVisitor, Type targetClass, Type annotationType) {
        methodVisitor.visitLdcInsn(targetClass);
        pushGetAnnotationCall(methodVisitor, annotationType);
    }

    private void pushGetAnnotationCall(MethodVisitor methodVisitor, Type annotationType) {
        methodVisitor.visitLdcInsn(annotationType);
        Method method = ReflectionUtils.getDeclaredMethod(Class.class, "getAnnotation", Class.class)
                .orElseThrow(() ->
                        new IllegalStateException("Class.getAnnotation(..) method not found")
                );
        String descriptor = Type.getType(method).getDescriptor();
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                Type.getInternalName(Class.class),
                "getAnnotation",
                descriptor,
                false);
    }

    /**
     * Adds a method call to get the given annotation of the given type to tye stack
     *
     * @param annotationType The annotation type
     */
    private void pushGetAnnotationForField(MethodVisitor methodVisitor, Type annotationType) {
        methodVisitor.visitLdcInsn(annotationType);
        Method method = ReflectionUtils.getDeclaredMethod(Field.class, "getAnnotation", Class.class)
                .orElseThrow(() ->
                        new IllegalStateException("Field.getAnnotation(..) method not found. Incompatible JVM?")
                );
        String descriptor = Type.getType(method).getDescriptor();
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                Type.getInternalName(Field.class),
                "getAnnotation",
                descriptor,
                false);
    }

    private String generateBeanDefSig(String typeParameter) {
        SignatureVisitor sv = new SignatureWriter();
        visitSuperTypeParameters(sv, typeParameter);

        String beanTypeInternalName = getInternalName(typeParameter);
        // visit BeanFactory interface
        for (Class interfaceType : interfaceTypes) {

            SignatureVisitor bfi = sv.visitInterface();
            bfi.visitClassType(Type.getInternalName(interfaceType));
            SignatureVisitor iisv = bfi.visitTypeArgument('=');
            iisv.visitClassType(beanTypeInternalName);
            iisv.visitEnd();
            bfi.visitEnd();
        }
//        sv.visitEnd();
        return sv.toString();
    }

    private void visitSuperTypeParameters(SignatureVisitor sv, String... typeParameters) {
        // visit super class
        SignatureVisitor psv = sv.visitSuperclass();
        psv.visitClassType(isSuperFactory ? TYPE_ABSTRACT_BEAN_DEFINITION.getInternalName() : superType.getInternalName());
        if (superType == TYPE_ABSTRACT_BEAN_DEFINITION || superType == TYPE_ABSTRACT_PARAMETRIZED_BEAN_DEFINITION || isSuperFactory) {
            for (String typeParameter : typeParameters) {

                SignatureVisitor ppsv = psv.visitTypeArgument('=');
                String beanTypeInternalName = getInternalName(typeParameter);
                ppsv.visitClassType(beanTypeInternalName);
                ppsv.visitEnd();
            }
        }

        psv.visitEnd();
    }
}
