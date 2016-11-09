package injecter.processor;

import com.google.auto.common.SuperficialValidation;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

import injecter.annotation.BindView;

import static com.squareup.javapoet.ClassName.bestGuess;

/**
 * 注解处理器
 */
@AutoService(Processor.class)
public final class InjecterProcessor extends AbstractProcessor{

    private Elements elementUtils;
    private Filer filer; //生成源代码
    private Messager messager; //打印信息
    private static final ClassName VIEW_BINDER = ClassName.get("injecter.api", "ViewBinder");//实现的接口

    private static final String BINDING_CLASS_SUFFIX = "$$ViewBinder";//生成类的后缀 以后会用反射去取

    @Override public synchronized void init(ProcessingEnvironment env) {
        super.init(env);

        elementUtils = env.getElementUtils();
        filer = env.getFiler();
        messager = env.getMessager();
    }

    /**
     * 需要处理的注解
     * @return
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> types = new LinkedHashSet<>();
        types.add(BindView.class.getCanonicalName());
        return types;
    }

    /**
     * 支持的源码版本
     * @return
     */
    @Override public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * 获取注解信息，动态生成代码
     * @param annotations
     * @param roundEnv
     * @return
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<TypeElement, List<FieldViewBinding>> targetClassMap = new LinkedHashMap<>();
        Set<? extends Element> elementsAnnotateds= roundEnv.getElementsAnnotatedWith(BindView.class);
        for (Element element:elementsAnnotateds){
            messager.printMessage(Diagnostic.Kind.WARNING, "Printing: " + element);
            if (!SuperficialValidation.validateElement(element))
                continue;
            // Start by verifying common generated code restrictions.
            TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
            boolean hasError = isInaccessibleViaGeneratedCode(BindView.class, "fields", element)
                        || isBindingInWrongPackage(BindView.class, element);
            // Verify that the target type extends from View.
            TypeMirror elementType = element.asType();
            if (elementType.getKind() == TypeKind.TYPEVAR) {
                TypeVariable typeVariable = (TypeVariable) elementType;
                elementType = typeVariable.getUpperBound();
            }
            if (!isSubtypeOfType(elementType, "android.view.View") && !isInterface(elementType)) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("@%s fields must extend from View or be an interface. (%s.%s)",
                        BindView.class.getSimpleName(), enclosingElement.getQualifiedName(),
                        element.getSimpleName()));
                hasError = true;
            }

            if (hasError) {
                continue;
            }

            // Assemble information on the field.


            List<FieldViewBinding> fieldViewBindingList = targetClassMap.get(enclosingElement);

            if (fieldViewBindingList == null) {
                fieldViewBindingList = new ArrayList<>();
                targetClassMap.put(enclosingElement, fieldViewBindingList);
            }


            String packageName = getPackageName(enclosingElement);
            TypeName targetType = TypeName.get(enclosingElement.asType());
            int id = element.getAnnotation(BindView.class).value();
            String fieldName = element.getSimpleName().toString();
            TypeMirror fieldType = element.asType();

            FieldViewBinding fieldViewBinding = new FieldViewBinding(fieldName, fieldType, id);
            fieldViewBindingList.add(fieldViewBinding);
        }

        for (Map.Entry<TypeElement, List<FieldViewBinding>> item : targetClassMap.entrySet()){
            List<FieldViewBinding> list = item.getValue();
            if (list == null || list.size() == 0){
                continue;
            }

            //创建.java源文件
            TypeElement enclosingElement = item.getKey();
            String packageName = getPackageName(enclosingElement);
            ClassName typeClassName = ClassName.bestGuess(getClassName(enclosingElement, packageName));
            TypeSpec.Builder result = TypeSpec.classBuilder(getClassName(enclosingElement, packageName) + BINDING_CLASS_SUFFIX)
                    .addModifiers(Modifier.PUBLIC) //public
                    .addTypeVariable(TypeVariableName.get("T", typeClassName)) //泛型变量
                    .addSuperinterface(ParameterizedTypeName.get(VIEW_BINDER, typeClassName));//实现的接口
            result.addMethod(createBindMethod(list, typeClassName));
            try {
                JavaFile.builder(packageName, result.build())
                        .addFileComment(" This codes are generated automatically. Do not modify!")
                        .build().writeTo(filer);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return true;
    }

    private MethodSpec createBindMethod(List<FieldViewBinding> list, ClassName typeClassName) {
        MethodSpec.Builder result = MethodSpec.methodBuilder("bind")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addAnnotation(Override.class)
                .addParameter(typeClassName, "target", Modifier.FINAL);

        for (int i = 0; i < list.size(); i++) {
            FieldViewBinding fieldViewBinding = list.get(i);

            String packageString = fieldViewBinding.getType().toString();
//            String className = fieldViewBinding.getType().getClass().getSimpleName();
            ClassName viewClass = bestGuess(packageString);
            result.addStatement("target.$L=($T)target.findViewById($L)", fieldViewBinding.getName(), viewClass, fieldViewBinding.getResId());
        }
        return result.build();
    }


    private boolean isInaccessibleViaGeneratedCode(Class<? extends Annotation> annotationClass,
                                                   String targetThing, Element element) {
        boolean hasError = false;
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

        // Verify method modifiers.
        Set<Modifier> modifiers = element.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.STATIC)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("@%s %s must not be private or static. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName()));
            hasError = true;
        }

        // Verify containing type.
        if (enclosingElement.getKind() != ElementKind.CLASS) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("@%s %s may only be contained in classes. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName()));
            hasError = true;
        }

        // Verify containing class visibility is not private.
        if (enclosingElement.getModifiers().contains(Modifier.PRIVATE)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("@%s %s may not be contained in private classes. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName()));
            hasError = true;
        }

        return hasError;
    }

    private boolean isBindingInWrongPackage(Class<? extends Annotation> annotationClass,
                                            Element element) {
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
        String qualifiedName = enclosingElement.getQualifiedName().toString();

        if (qualifiedName.startsWith("android.")) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("@%s-annotated class incorrectly in Android framework package. (%s)",
                    annotationClass.getSimpleName(), qualifiedName));
            return true;
        }
        if (qualifiedName.startsWith("java.")) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, String.format("@%s-annotated class incorrectly in Java framework package. (%s)",
                    annotationClass.getSimpleName(), qualifiedName));
            return true;
        }

        return false;
    }

    private boolean isSubtypeOfType(TypeMirror typeMirror, String otherType) {
        if (otherType.equals(typeMirror.toString())) {
            return true;
        }
        if (typeMirror.getKind() != TypeKind.DECLARED) {
            return false;
        }
        DeclaredType declaredType = (DeclaredType) typeMirror;
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        if (typeArguments.size() > 0) {
            StringBuilder typeString = new StringBuilder(declaredType.asElement().toString());
            typeString.append('<');
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) {
                    typeString.append(',');
                }
                typeString.append('?');
            }
            typeString.append('>');
            if (typeString.toString().equals(otherType)) {
                return true;
            }
        }
        Element element = declaredType.asElement();
        if (!(element instanceof TypeElement)) {
            return false;
        }
        TypeElement typeElement = (TypeElement) element;
        TypeMirror superType = typeElement.getSuperclass();
        if (isSubtypeOfType(superType, otherType)) {
            return true;
        }
        for (TypeMirror interfaceType : typeElement.getInterfaces()) {
            if (isSubtypeOfType(interfaceType, otherType)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInterface(TypeMirror typeMirror) {
        return typeMirror instanceof DeclaredType
                && ((DeclaredType) typeMirror).asElement().getKind() == ElementKind.INTERFACE;
    }

    private String getPackageName(TypeElement type) {
        return elementUtils.getPackageOf(type).getQualifiedName().toString();
    }

    private static String getClassName(TypeElement type, String packageName) {
        int packageLen = packageName.length() + 1;
        return type.getQualifiedName().toString().substring(packageLen).replace('.', '$');
    }
}