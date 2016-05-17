package io.fabianterhorst.fastlayout.processor;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.Version;
import io.fabianterhorst.fastlayout.annotations.Layout;
import io.fabianterhorst.fastlayout.annotations.Layouts;

@SupportedAnnotationTypes("io.fabianterhorst.fastlayout.annotations.Layouts")
public class LayoutProcessor extends AbstractProcessor {

    private static final ArrayList<String> nativeSupportedAttributes = new ArrayList<String>() {{
        add("layout_height");
        add("layout_width");
        add("id");
        add("padding");
        add("paddingStart");
        add("paddingEnd");
        add("paddingLeft");
        add("paddingTop");
        add("paddingRight");
        add("paddingBottom");
        add("layout_margin");
        add("layout_marginLeft");
        add("layout_marginTop");
        add("layout_marginRight");
        add("layout_marginBottom");
        add("layout_weight");
    }};

    private static final String SUFFIX_PREF_WRAPPER = "Layout";

    private Configuration mFreemarkerConfiguration;

    private List<LayoutEntity> mChilds;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    private Configuration getFreemarkerConfiguration() {
        if (mFreemarkerConfiguration == null) {
            mFreemarkerConfiguration = new Configuration(new Version(2, 3, 22));
            mFreemarkerConfiguration.setClassForTemplateLoading(getClass(), "");
        }
        return mFreemarkerConfiguration;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        File layoutsFile = null;
        String packageName = null;
        List<LayoutObject> layouts = new ArrayList<>();
        String rOutput = null;
        try {
            if (annotations.size() > 0) {
                layoutsFile = findLayouts();
            }
            for (TypeElement te : annotations) {
                for (javax.lang.model.element.Element element : roundEnv.getElementsAnnotatedWith(te)) {
                    TypeElement classElement = (TypeElement) element;
                    PackageElement packageElement = (PackageElement) classElement.getEnclosingElement();
                    packageName = packageElement.getQualifiedName().toString();
                    Layouts layoutsAnnotation = element.getAnnotation(Layouts.class);

                    for (String layoutName : layoutsAnnotation.layouts()) {
                        LayoutObject layoutObject = createLayoutObject(layoutsFile, layoutName, packageElement, element, constantToObjectName(layoutName));
                        if (layoutObject == null) {
                            return true;
                        }
                        layouts.add(layoutObject);
                    }

                    for (int layoutId : layoutsAnnotation.ids()) {
                        if (rOutput == null) {
                            File r = findR(packageName);
                            rOutput = readFile(r);
                        }
                        String layoutName = getFieldNameFromLayoutId(rOutput, layoutId);
                        LayoutObject layoutObject = createLayoutObject(layoutsFile, layoutName, packageElement, element, constantToObjectName(layoutName));
                        if (layoutObject == null) {
                            return true;
                        }
                        layouts.add(layoutObject);
                    }

                    for (VariableElement variableElement : ElementFilter.fieldsIn(classElement.getEnclosedElements())) {
                        TypeMirror fieldType = variableElement.asType();//ILayout
                        String fieldName = variableElement.getSimpleName().toString();
                        String layoutName = fieldName;
                        Layout layoutAnnotation = variableElement.getAnnotation(Layout.class);
                        if (layoutAnnotation != null) {
                            layoutName = layoutAnnotation.name();
                        }

                        LayoutObject layoutObject = createLayoutObject(layoutsFile, layoutName, packageElement, element, constantToObjectName(fieldName));
                        if (layoutObject == null) {
                            return true;
                        }
                        layouts.add(layoutObject);
                    }

                    if (layoutsAnnotation.all() && layoutsFile != null) {
                        File[] files = layoutsFile.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                String layoutName = file.getName().replace(".xml", "");
                                LayoutObject layoutObject = createLayoutObject(readFile(file), packageElement, element, constantToObjectName(layoutName));
                                if (layoutObject == null) {
                                    return true;
                                }
                                layouts.add(layoutObject);
                            }
                        }
                    }
                }
            }
            if (layouts.size() > 0 && packageName != null) {
                boolean cacheCreated = createLayoutCacheObject(layouts, packageName);
                if (!cacheCreated) {
                    return true;
                }
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, String.valueOf(layouts.size()) + " layout" + (layouts.size() > 1 ? "s" : "") + " generated.");
            }
        } catch (Exception exception) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, exception.getMessage());
        }
        return true;
    }

    private String normalizeLayoutId(String layoutId) {
        return layoutId.replace("@+id/", "").replace("@id/", "");
    }

    private String getIdByNode(Node node) {
        return normalizeLayoutId(node.getAttributes().getNamedItem("android:id").getNodeValue());
    }

    private List<LayoutEntity> createChildList(Node node) {
        List<LayoutEntity> layouts = new ArrayList<>();
        NodeList nodeList = node.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node child = nodeList.item(i);
            if (child.getAttributes() != null && child.getAttributes().getLength() > 0) {
                LayoutEntity layout = createLayoutFromChild(child, node.getNodeName());
                if (node.getNodeName().equals("RelativeLayout")) {
                    layout.setRelative(true);
                }
                mChilds.add(layout);
                layouts.add(layout);
                if (child.hasChildNodes()) {
                    createChildList(child);
                }
            }
        }
        return layouts;
    }

    private LayoutEntity createLayoutFromChild(Node node) {
        return createLayoutFromChild(node, node.getNodeName());
    }

    private LayoutEntity createLayoutFromChild(Node node, String root) {
        LayoutEntity layout = new LayoutEntity();
        layout.setId(getIdByNode(node));
        layout.setName(node.getNodeName());
        layout.setHasChildren(node.hasChildNodes());
        layout.setRootLayout(root);
        LayoutParam layoutParams = new LayoutParam();
        layoutParams.setName(root + ".LayoutParams");
        layoutParams.setWidth(node.getAttributes().getNamedItem("android:layout_width").getNodeValue().toUpperCase());
        layoutParams.setHeight(node.getAttributes().getNamedItem("android:layout_height").getNodeValue().toUpperCase());
        Object paddingLeft = null;
        Object paddingTop = null;
        Object paddingRight = null;
        Object paddingBottom = null;
        Object paddingStart = null;
        Object paddingEnd = null;
        Object padding = null;
        if (node.getAttributes().getNamedItem("android:paddingLeft") != null) {
            paddingLeft = getLayoutAttribute(node.getAttributes().getNamedItem("android:paddingLeft").getNodeValue()).getValue();
        }
        if (node.getAttributes().getNamedItem("android:paddingTop") != null) {
            paddingTop = getLayoutAttribute(node.getAttributes().getNamedItem("android:paddingTop").getNodeValue()).getValue();
        }
        if (node.getAttributes().getNamedItem("android:paddingRight") != null) {
            paddingRight = getLayoutAttribute(node.getAttributes().getNamedItem("android:paddingRight").getNodeValue()).getValue();
        }
        if (node.getAttributes().getNamedItem("android:paddingBottom") != null) {
            paddingBottom = getLayoutAttribute(node.getAttributes().getNamedItem("android:paddingBottom").getNodeValue()).getValue();
        }
        if (node.getAttributes().getNamedItem("android:paddingStart") != null) {
            paddingStart = getLayoutAttribute(node.getAttributes().getNamedItem("android:paddingStart").getNodeValue()).getValue();
        }
        if (node.getAttributes().getNamedItem("android:paddingEnd") != null) {
            paddingEnd = getLayoutAttribute(node.getAttributes().getNamedItem("android:paddingEnd").getNodeValue()).getValue();
        }
        if (paddingLeft != null || paddingTop != null || paddingRight != null || paddingBottom != null) {
            layoutParams.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
        }
        if (paddingStart != null || paddingEnd != null) {
            layoutParams.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom);
        }
        if (node.getAttributes().getNamedItem("android:padding") != null) {
            padding = getLayoutAttribute(node.getAttributes().getNamedItem("android:padding").getNodeValue()).getValue();
        }
        if (padding != null) {
            layoutParams.setPadding(padding, padding, padding, padding);
            layoutParams.setPaddingRelative(padding, padding, padding, padding);
        }
        Object marginLeft = null;
        Object marginTop = null;
        Object marginRight = null;
        Object marginBottom = null;
        Object margin = null;
        if (node.getAttributes().getNamedItem("android:layout_marginLeft") != null) {
            marginLeft = getLayoutAttribute(node.getAttributes().getNamedItem("android:layout_marginLeft").getNodeValue()).getValue();
        }
        if (node.getAttributes().getNamedItem("android:layout_marginTop") != null) {
            marginTop = getLayoutAttribute(node.getAttributes().getNamedItem("android:layout_marginTop").getNodeValue()).getValue();
        }
        if (node.getAttributes().getNamedItem("android:layout_marginRight") != null) {
            marginRight = getLayoutAttribute(node.getAttributes().getNamedItem("android:layout_marginRight").getNodeValue()).getValue();
        }
        if (node.getAttributes().getNamedItem("android:layout_marginBottom") != null) {
            marginBottom = getLayoutAttribute(node.getAttributes().getNamedItem("android:layout_marginBottom").getNodeValue()).getValue();
        }
        if (marginLeft != null || marginTop != null || marginRight != null || marginBottom != null) {
            layoutParams.setMargins(marginLeft, marginTop, marginRight, marginBottom);
        }
        if (node.getAttributes().getNamedItem("android:layout_margin") != null) {
            margin = getLayoutAttribute(node.getAttributes().getNamedItem("android:layout_margin").getNodeValue()).getValue();
        }
        if (margin != null) {
            layoutParams.setMargins(margin, margin, margin, margin);
        }
        if (node.getAttributes().getNamedItem("android:layout_weight") != null) {
            Object weight = getLayoutAttribute(node.getAttributes().getNamedItem("android:layout_weight").getNodeValue()).getValue();
            layoutParams.setWeight(weight);
        }
        layout.setLayoutParams(layoutParams);
        NamedNodeMap attributes = node.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String attributeName = attribute.getNodeName();
            String attributeValue = attribute.getNodeValue();
            if (attributeName.contains("android:")) {
                String newName = attributeName.replace("android:", "");
                if (!nativeSupportedAttributes.contains(newName)) {
                    String[] split = newName.split("_");
                    newName = "";
                    for (String refactor : split) {
                        newName += StringUtils.capitalize(refactor);
                    }

                    LayoutAttribute layoutAttribute = getLayoutAttribute(attributeValue, newName, node);
                    boolean string = layoutAttribute.isString();
                    Object value = layoutAttribute.getValue();

                    String relativeName = getRelativeLayoutParam(newName.replace("Layout", ""));
                    if (relativeName != null && relativeName.contains("_") && attributeName.startsWith("android:layout_") && !attributeName.startsWith("android:layout_margin")) {
                        if (!value.equals("false")) {
                            layout.addLayoutParam(relativeName, getLayoutId(value), true, true);
                        }
                    } else if (attributeName.equals("android:layout_gravity")) {
                        layout.addLayoutParam(newName.replace("Layout", "").toLowerCase(), value, true, false, !string, true);
                    } else if (attributeName.equals("android:layout_marginEnd") || attributeName.equals("android:layout_marginStart")) {
                        layout.addLayoutParam(newName.replace("Layout", ""), value, true, false, !string);
                    } else {
                        layout.addLayoutParam(newName, value, false, false, !string);
                    }
                }
            } else if (attributeName.contains("app:")) {
                String newName = attributeName.replace("android:", "");
                String[] split = newName.split("_");
                newName = "";
                for (String refactor : split) {
                    newName += StringUtils.capitalize(refactor);
                }

                LayoutAttribute layoutAttribute = getLayoutAttribute(attributeValue, newName, node);
                boolean string = layoutAttribute.isString();
                Object value = layoutAttribute.getValue();

                layout.addLayoutParam(newName, value, false, false, !string);

            }
        }
        return layout;
    }

    private Object getLayoutId(Object value) {
        String id = String.valueOf(value);
        if (id.contains("@id/")) {
            return id.replace("@id/", "R.id.");
        } else if (id.contains("@+id/")) {
            return id.replace("@+id/", "R.id.");
        }
        return value;
    }

    private LayoutAttribute getLayoutAttribute(String attribute) {
        return getLayoutAttribute(attribute, null, null);
    }

    private LayoutAttribute getLayoutAttribute(String attribute, String attributeName, Node node) {
        if (attribute.startsWith("@dimen/")) {
            return new LayoutAttribute("(int) getContext().getResources().getDimension(R.dimen." + attribute.replace("@dimen/", "") + ")", false);
        } else if (attribute.startsWith("@string/")) {
            return new LayoutAttribute("getContext().getString(R.string." + attribute.replace("@string/", "") + ")", false);
        } else if (attribute.endsWith("dp") && StringUtils.isNumeric(attribute.replace("dp", ""))) {
            return new LayoutAttribute("(int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, " + attribute.replace("dp", "") + ", getResources().getDisplayMetrics())", false);
        } else if (attribute.startsWith("?attr/") && attributeName != null && attributeName.equals("Background")) {
            return new LayoutAttribute("LayoutUtils.getAttrDrawable(getContext(), R.attr." + attribute.replace("?attr/", "") + ")", false);
        } else if (attribute.startsWith("?attr/")) {
            return new LayoutAttribute("LayoutUtils.getAttrInt(getContext(), R.attr." + attribute.replace("?attr/", "") + ")", false);
        } else if (attributeName != null && attributeName.equals("Orientation")) {
            return new LayoutAttribute(node.getNodeName() + "." + attribute.toUpperCase(), false);
        } else if (attributeName != null && (attributeName.equals("Gravity") || attributeName.equals("LayoutGravity"))) {
            return new LayoutAttribute("Gravity." + attribute.toUpperCase(), false);
        } else if (attribute.equals("false") || attribute.equals("true")) {
            return new LayoutAttribute(attribute, false);
        } else if (attribute.endsWith("sp") && isNumber(attribute.replace("sp", "")) && attributeName != null && attributeName.equals("TextSize")) {
            return new LayoutAttribute("TypedValue.COMPLEX_UNIT_SP, " + attribute.replace("sp", ""), false);
        } else if (attribute.endsWith("sp") && isNumber(attribute.replace("sp", ""))) {
            return new LayoutAttribute("(int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, " + attribute.replace("sp", "") + ", Resources.getSystem().getDisplayMetrics())", false);
        } else {
            try {
                return new LayoutAttribute(Integer.parseInt(attribute), false);
            } catch (NumberFormatException ignore) {

            }
        }
        return new LayoutAttribute(attribute, true);
    }

    /**
     * convert for example AlignParentBottom to RelativeLayout.ALIGN_PARENT_BOTTOM
     *
     * @param name attribute name
     * @return relative layout attribute
     */
    private String getRelativeLayoutParam(String name) {
        if (name.startsWith("To")) {
            name = name.replace("To", "");
        }
        return "RelativeLayout." + stringToConstant(name).toUpperCase();
    }

    /**
     * convert a string to a constant schema
     *
     * @param string string
     * @return constant schema string
     */
    private String stringToConstant(String string) {
        int length = string.length();
        for (int i = 0; i < length; i++) {
            char character = string.charAt(i);
            if (character != "_".charAt(0) && Character.isUpperCase(character) && i != 0) {
                String firstPart = string.substring(0, i);
                String secondPart = string.substring(i, length);
                String newFirstPart = firstPart + "_";
                string = newFirstPart + secondPart;
                i = newFirstPart.length();
                length++;
            }
        }
        return string;
    }

    /**
     * convert a constant to a object name schema
     *
     * @param string constant
     * @return object name schema
     */
    private String constantToObjectName(String string) {
        if (!Character.isUpperCase(string.charAt(0))) {
            string = StringUtils.capitalize(string);
            int length = string.length();
            for (int i = 0; i < length; i++) {
                char character = string.charAt(i);
                if (character == "_".charAt(0)) {
                    String firstPart = string.substring(0, i);
                    String secondPart = string.substring(i + 1, length);
                    String newSecondPart = StringUtils.capitalize(secondPart);
                    string = firstPart + newSecondPart;
                    i = firstPart.length();
                    length--;
                }
            }
        }
        return string;
    }

    private File getProjectRoot() throws Exception {
        Filer filer = processingEnv.getFiler();

        JavaFileObject dummySourceFile = filer.createSourceFile("dummy" + System.currentTimeMillis());
        String dummySourceFilePath = dummySourceFile.toUri().toString();

        if (dummySourceFilePath.startsWith("file:")) {
            if (!dummySourceFilePath.startsWith("file://")) {
                dummySourceFilePath = "file://" + dummySourceFilePath.substring("file:".length());
            }
        } else {
            dummySourceFilePath = "file://" + dummySourceFilePath;
        }

        URI cleanURI = new URI(dummySourceFilePath);

        File dummyFile = new File(cleanURI);

        return dummyFile.getParentFile();
    }

    private File findLayouts() throws Exception {
        File projectRoot = getProjectRoot();

        File sourceFile = new File(projectRoot.getAbsolutePath() + "/src/main/res/layout");

        while (true) {
            if (sourceFile.exists()) {
                return sourceFile;
            } else {
                if (projectRoot.getParentFile() != null) {
                    projectRoot = projectRoot.getParentFile();
                    sourceFile = new File(projectRoot.getAbsolutePath() + "/src/main/res/layout");
                } else {
                    break;
                }
            }
        }
        return new File(projectRoot.getAbsolutePath() + "/src/main/res/layout");
    }

    private File findR(String packageName) throws Exception {
        File projectRoot = getProjectRoot();

        String packagePath = packageName.replace(".", "/");

        File sourceFile = new File(projectRoot.getAbsolutePath() + "/r/debug/" + packagePath + "/R.java");

        while (true) {
            if (sourceFile.exists()) {
                return sourceFile;
            } else {
                if (projectRoot.getParentFile() != null) {
                    projectRoot = projectRoot.getParentFile();
                    sourceFile = new File(projectRoot.getAbsolutePath() + "/r/debug/" + packagePath + "/R.java");
                } else {
                    break;
                }
            }
        }
        return new File(projectRoot.getAbsolutePath() + "/src/main/res/layout");
    }

    private String getFieldNameFromLayoutId(String rOutput, int layoutId) {
        String hex = "0x" + Integer.toHexString(layoutId);
        int index = rOutput.indexOf(hex);
        String subToLayout = rOutput.substring(0, index);
        String[] splitFields = subToLayout.split("=");
        String lastField = splitFields[splitFields.length - 1];
        String[] lastFieldNameSplit = lastField.split(" ");
        return lastFieldNameSplit[lastFieldNameSplit.length - 1];
    }

    private File findLayout(File layouts, String layoutName) throws Exception {
        return new File(layouts, layoutName + ".xml");
    }

    @SuppressWarnings("NewApi")
    private String readFile(File file) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(file)) {
            return IOUtils.toString(inputStream);
        }
    }

    @SuppressWarnings("Ignore")
    private boolean isNumber(Object text) {
        try {
            Integer.parseInt(String.valueOf(text));
            return true;
        } catch (NumberFormatException ignore) {
            return false;
        }
    }

    private LayoutObject createLayoutObject(File layoutsFile, String layoutName, PackageElement packageElement, javax.lang.model.element.Element element, String fieldName) throws Exception {
        return createLayoutObject(readFile(findLayout(layoutsFile, layoutName)), packageElement, element, fieldName);
    }

    private LayoutObject createLayoutObject(String layout, PackageElement packageElement, javax.lang.model.element.Element element, String fieldName) throws Exception {
        mChilds = new ArrayList<>();
        LayoutEntity rootLayout = new LayoutEntity();
        try {
            DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            InputSource is = new InputSource();
            is.setCharacterStream(new StringReader(layout));
            Document document = documentBuilder.parse(is);
            Element rootLayoutElement = document.getDocumentElement();
            rootLayout = createLayoutFromChild(rootLayoutElement);
            if (rootLayoutElement.hasChildNodes()) {
                createChildList(rootLayoutElement);
                rootLayout.addChildren(mChilds);
            }
        } catch (Exception ignore) {
        }

        JavaFileObject javaFileObject;
        try {
            String layoutObjectName = packageElement.getQualifiedName().toString() + "." + fieldName + SUFFIX_PREF_WRAPPER;
            Map<String, Object> args = new HashMap<>();
            //Layout Wrapper
            javaFileObject = processingEnv.getFiler().createSourceFile(layoutObjectName);
            Template template = getFreemarkerConfiguration().getTemplate("layout.ftl");
            args.put("package", packageElement.getQualifiedName());
            args.put("keyWrapperClassName", fieldName + SUFFIX_PREF_WRAPPER);
            args.put("rootLayout", rootLayout);
            Writer writer = javaFileObject.openWriter();
            template.process(args, writer);
            IOUtils.closeQuietly(writer);
            return new LayoutObject(layoutObjectName);
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "En error occurred while generating Prefs code " + e.getClass() + e.getMessage(), element);
            e.printStackTrace();
            // Problem detected: halt
            return null;
        }
    }

    private boolean createLayoutCacheObject(List<LayoutObject> layouts, String packageName) {
        JavaFileObject javaFileObject;
        try {
            Map<String, LayoutObject> layoutMap = new HashMap<>();
            for (LayoutObject layout : layouts) {
                String name = layout.getName();
                layoutMap.put(stringToConstant(name.replace(packageName + ".", "")), layout);
            }

            Map<String, Object> args = new HashMap<>();
            //Layout Cache Wrapper
            javaFileObject = processingEnv.getFiler().createSourceFile(packageName + ".LayoutCache");
            Template template = getFreemarkerConfiguration().getTemplate("layoutcache.ftl");
            args.put("package", packageName);
            args.put("layouts", layoutMap);
            Writer writer = javaFileObject.openWriter();
            template.process(args, writer);
            IOUtils.closeQuietly(writer);
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "En error occurred while generating Prefs code " + e.getClass() + e.getMessage());
            e.printStackTrace();
            // Problem detected: halt
            return false;
        }
        return true;
    }
}
