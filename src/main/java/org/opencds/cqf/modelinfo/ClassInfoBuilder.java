package org.opencds.cqf.modelinfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.hl7.elm_modelinfo.r1.ChoiceTypeSpecifier;
import org.hl7.elm_modelinfo.r1.ClassInfo;
import org.hl7.elm_modelinfo.r1.ClassInfoElement;
import org.hl7.elm_modelinfo.r1.ListTypeSpecifier;
import org.hl7.elm_modelinfo.r1.NamedTypeSpecifier;
import org.hl7.elm_modelinfo.r1.TypeInfo;
import org.hl7.elm_modelinfo.r1.TypeSpecifier;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Element;
import org.hl7.fhir.r4.model.ElementDefinition;
import org.hl7.fhir.r4.model.ElementDefinition.TypeRefComponent;
import org.hl7.fhir.r4.model.Enumerations.BindingStrength;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.hl7.fhir.r4.model.StructureDefinition.StructureDefinitionKind;

public abstract class ClassInfoBuilder {
    protected Map<String, StructureDefinition> structureDefinitions;
    protected Map<String, TypeInfo> typeInfos = new HashMap<String, TypeInfo>();
    protected ClassInfoSettings settings;
    private boolean needsTopLevelSD = false;

    public ClassInfoBuilder(ClassInfoSettings settings, Map<String, StructureDefinition> structureDefinitions) {
        this.structureDefinitions = structureDefinitions;
        this.settings = settings;
    }

    protected abstract void innerBuild();
    protected abstract void afterBuild();
    public Map<String, TypeInfo> build() {
        this.innerBuild();
        return this.getTypeInfos();
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }

    private String getHead(String url) {
        int index = url.lastIndexOf("/");
        if (index == -1) {
            return null;
        } else if (index > 0) {
            return url.substring(0, index);
        } else {
            return "";
        }
    }

    private String getTail(String url) {
        int index = url.lastIndexOf("/");
        if (index == -1) {
            return null;
        } else if (index > 0) {
            return url.substring(index + 1);
        } else {
            return "";
        }
    }

    private String resolveModelName(String url) throws Exception {
        // Strips off the identifier and type name
        String model = getHead(getHead(url));
        if (this.settings.urlToModel.containsKey(model)) {
            return this.settings.urlToModel.get(model);
        }

        throw new Exception("Couldn't resolve model name for url: " + url);
    }

    private String resolveTypeName(String url) throws Exception {
        if (url != null) {
            String modelName = resolveModelName(url);
            if (getTail(url).contains("-"))
            {
                return getTypeName(modelName, capitalize(unQualify(getTail(url))));
            }
            else return getTypeName(modelName, getTail(url));
        }

        return null;
    }

    private String getTypeName(String modelName, String typeName) {
        return modelName != null ? modelName + "." + typeName : typeName;
    }

    protected String getTypeName(NamedTypeSpecifier typeSpecifier) {
        return this.getTypeName(typeSpecifier.getModelName(), typeSpecifier.getName());
    }

    protected TypeInfo resolveType(String name) {
        return this.typeInfos.get(name);
    }

    private TypeInfo resolveType(TypeSpecifier typeSpecifier) {
        if (typeSpecifier instanceof NamedTypeSpecifier) {
            return this.resolveType(((NamedTypeSpecifier) typeSpecifier).getName());
        } else {
            ListTypeSpecifier lts = (ListTypeSpecifier) typeSpecifier;
            if (lts.getElementType() != null) {
                return this.resolveType(lts.getElementType());
            } else {
                return this.resolveType(lts.getElementTypeSpecifier());
            }
        }
    }

    private TypeInfo resolveType(ClassInfoElement classInfoElement) {
        if (classInfoElement.getElementType() != null) {
            return this.resolveType(classInfoElement.getElementType());
        } else {
            return this.resolveType(classInfoElement.getElementTypeSpecifier());
        }
    }

    protected String getQualifier(String name) {
        if (name == null) {
            return null;
        }
        int index = name.indexOf(".");
        if (index > 0) {
            return name.substring(0, index);
        }

        return null;
    }

    protected String unQualify(String name) {
        if (name == null) {
            return null;
        }
        if (name.contains(".")) {
            int index = name.indexOf(".");
            if (index > 0) {
                return name.substring(index + 1);
            }

            return null;
        }
        // BTR -> Need to understand why this is here. If this is required at all, it should be a separate function, unHyphenate or something like that.
        else if (name.contains("-")) {
            int index = name.lastIndexOf("-");
            if (index > 0) {
                return name.substring(index + 1);
            }

            return null;
        }

        return null;
    }

    // Returns the given string with the first letter capitalized
    private String capitalize(String name) {
        if (name.length() >= 1) {
            return name.substring(0, 1).toUpperCase() + name.substring(1);
        }

        return name;
    }

    // Returns the given path with the first letter of every path capitalized
    private String capitalizePath(String path) {
        if (path.contains("-")) {
            return String.join("_", Arrays.asList(path.split("\\-")).stream().map(x -> this.capitalize(x))
                    .collect(Collectors.toList()));
        } else {
            return String.join(".", Arrays.asList(path.split("\\.")).stream().map(x -> this.capitalize(x))
                    .collect(Collectors.toList()));
        }
    }

    private String capitalizePath(String path, String modelName) {
        if (path.contains("-")) {
            return String.join("_", Arrays.asList(path.split("\\-")).stream().map(x -> this.capitalize(x))
                    .collect(Collectors.toList()));
        } else if (!path.contains(modelName + ".")) {
            return String.join(".", Arrays.asList(path.split("\\.")).stream().map(x -> this.capitalize(x))
                    .collect(Collectors.toList()));
        } else
            return path;
    }

    // Returns the name of the component type used to represent anonymous nested
    // structures
    private String getComponentTypeName(String path) {
        return this.capitalizePath(path) + "Component";
    }

    // Strips the given root from the given path.
    // Throws an error if the path does not start with the root.
    protected String stripRoot(String path, String root) throws Exception {
        int index = path.indexOf(root);
        if (index == -1) {
            throw new Exception("Path " + path + " does not start with the root " + root + ".");
        }

        String result = path.substring(root.length());

        if (result.startsWith(".")) {
            result = result.substring(1);
        }

        return result;
    }

    private String stripPath(String path) throws Exception {
        int index = path.lastIndexOf(".");
        if (index == -1) {
            throw new Exception("Path " + path + " does not have any continuation represented by " + ".");
        }

        String result = path.substring(index);

        if (result.startsWith(".")) {
            result = result.substring(1);
        }

        return result;
    }

    // Strips the [x] suffix of an element name which indicates a choice in FHIR
    private String stripChoice(String name) {
        int index = name.indexOf("[x]");
        if (index != -1) {
            return name.substring(0, index);
        }

        return name;
    }

    private String normalizeValueElement(String path) {
        int index = path.indexOf(".value");
        if (index != -1 && path.length() > (index + ".value".length())) {
            return path.substring(0, index);
        } else {
            return path;
        }
    }

    // Translates a path from the source root to the target root
    private String translate(String path, String sourceRoot, String targetRoot) {
        String result = this.normalizeValueElement(path);
        int sourceRootIndex = result.indexOf(sourceRoot);
        if (sourceRootIndex == 0) {
            result = targetRoot + result.substring(sourceRoot.length());
        }

        return result;
    }

    private StructureDefinition getRootStructureDefinition(StructureDefinition sd) {
        String baseSd = sd.getBaseDefinition();
        if (baseSd == null) {
            return sd;
        }

        return getRootStructureDefinition(structureDefinitions.get(getTail(baseSd)));
    }

    private StructureDefinition getTopLevelStructureDefinition(StructureDefinition sd, String path) {
        if (getTail(sd.getId()).equals(path)) {
            return sd;
        }

        return getTopLevelStructureDefinition(structureDefinitions.get(getTail(sd.getBaseDefinition())), path);
    }

    // Returns the value of the given string as an integer if it is integer-valued,
    // nil otherwise.
    private Integer asInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    // Returns true if the ElementDefinition describes a constraint only
    // For now, since constraints cannot be represented in ModelInfo, no element
    // will be created for these
    // For now, we are assuming that if the element specifies a type, it should be
    // declared
    // This may be a type constraint, which can be expressed in ModelInfo, so that's
    // okay, but if all
    // the element is changing is the binding or cardinality, no element will be
    // produced.
    private Boolean isConstraintElement(ElementDefinition ed) {
        if (ed.getType() != null && ed.getType().size() > 0) {
            return true;
        }

        return false;
    }

    // Returns true if the ElementDefinition describes a Backbone Element
    private Boolean isBackboneElement(ElementDefinition ed) {
        return ed.getType() != null && ed.getType().size() == 1
                && ed.getType().get(0).getCode().equals("BackboneElement");
    }

    // Returns true if the StructureDefinition is "BackboneElement"
    private Boolean isBackboneElement(StructureDefinition sd) {
        return getTail(sd.getId()).equals("BackboneElement");
    }

    // Returns true if the StructureDefinition is "Element"
    private Boolean isElement(StructureDefinition sd) {
        return getTail(sd.getId()).equals("Element");
    }

    private Boolean isExtension(StructureDefinition sd) {
        return getTail(sd.getId()).equals("Extension");
    }

    // Returns true if the ElementDefinition describes an Extension
    private Boolean isExtension(ElementDefinition ed) {
        return ed.getType() != null && ed.getType().size() == 1 && ed.getType().get(0).hasCode()
                && ed.getType().get(0).getCode() != null && ed.getType().get(0).getCode().equals("Extension");
    }

    // Returns the type code for the element if there is only one type ref
    private String typeCode(ElementDefinition ed) {
        if (ed.getType() != null && ed.getType().size() == 1) {
            return ed.getType().get(0).getCode();
        }

        return null;
    }

    // Returns true if the type specifier is a NamedTypeSpecifier referencing
    // FHIR.BackboneElement
    private Boolean isBackboneElement(TypeSpecifier typeSpecifier) {
        if (typeSpecifier instanceof NamedTypeSpecifier) {
            NamedTypeSpecifier nts = (NamedTypeSpecifier) typeSpecifier;
            String typeName = this.getTypeName(nts);
            return typeName != null && typeName.endsWith(".BackboneElement");
        }

        return false;
    }

    // Returns the set of element definitions for the given type id
    private List<ElementDefinition> getElementDefinitions(String typeId) {
        if (!structureDefinitions.containsKey(typeId)) {
            throw new RuntimeException("Could not retrieve element definitions for " + typeId);
        }

        return structureDefinitions.get(typeId).getSnapshot().getElement();
    }

    // Returns the set of element definitions for the given type
    private List<ElementDefinition> getElementDefinitions(TypeSpecifier typeSpecifier) {
        if (typeSpecifier instanceof NamedTypeSpecifier) {
            NamedTypeSpecifier nts = (NamedTypeSpecifier) typeSpecifier;
            if (nts.getName() != null) {
                return this.getElementDefinitions(nts.getName());
            }
        }

        return null;
    }

    // Returns the element definition for the given path
    private ElementDefinition elementForPath(List<ElementDefinition> elements, String path) {
        if (elements != null) {
            for (ElementDefinition ed : elements) {
                if (ed.getPath().equals(path)) {
                    return ed;
                }
            }
        }

        return null;
    }

    // Returns the given extension if it exists
    private Extension extension(Element element, String url) {
        if (element != null) {
            for (Extension ex : element.getExtension()) {
                if (ex.getUrl() != null && ex.getUrl().equals(url)) {
                    return ex;
                }
            }
        }

        return null;
    }

    // Returns true if the type is a "codeable" type (i.e. String, Code, Concept,
    // string, code, Coding, CodeableConcept)
    private Boolean isCodeable(String typeName) {
        return this.settings.codeableTypes.contains(typeName);
    }

    // Returns the primary code path for the given type, based on the following:
    // If the type has an entry in the PrimaryCodePaths table, the value there is
    // used
    // If the type has an element named "code" with a type of "String", "Code",
    // "Coding", or "CodeableConcept", that element is used
    private String primaryCodePath(List<ClassInfoElement> elements, String typeName) {
        if (this.settings.primaryCodePath.containsKey(typeName)) {
            return this.settings.primaryCodePath.get(typeName);
        } else if (elements != null) {
            for (ClassInfoElement e : elements) {
                if (e.getName().toLowerCase().equals("code") && this.isCodeable(e.getElementType())) {
                    return e.getName();
                }
            }

        }

        return null;
    }

    private String getLabel(StructureDefinition sd) {
        if (sd.hasTitle()) {
            return sd.getTitle();
        }

        return sd.getName();
    }

    private String unQualifyId(String id)
    {
        if(id == null)
        {
            return null;
        }
        if(id.contains("/"))
        {
            int index = id.indexOf("/");
            if (index > 0) {
                return id.substring(index + 1);
            }
        }

        if(id.contains("-"))
        {
            int index = id.indexOf("-");
            if (index > 0) {
                return id.substring(index + 1);
            }
        }
        

        return null;
    }

    // Returns the element with the given name, if it exists
    private ClassInfoElement element(List<ClassInfoElement> elements, String name) {
        if (elements != null) {
            for (ClassInfoElement cie : elements) {
                if (cie.getName().equals(name)) {
                    return cie;
                }
            }
        }

        return null;
    }

    //This is the start of the impactful logic... the above are mostly helper functions

    // Returns the element with the given path
    protected ClassInfoElement forPath(List<ClassInfoElement> elements, String path) {
        ClassInfoElement result = null;
        String[] segments = path.split("\\.");
        for (String p : segments) {
            result = element(elements, p);
            if (result != null) {
                TypeInfo elementType = resolveType(result);
                elements = ((ClassInfo) elementType).getElement();
            }
        }

        return result;
    }

    private TypeSpecifier buildTypeSpecifier(String modelName, String typeName) {
        NamedTypeSpecifier ts = new NamedTypeSpecifier();
        ts.setModelName(modelName);
        ts.setName(typeName);

        return ts;
    }

    private TypeSpecifier buildTypeSpecifier(String typeName) {
        return this.buildTypeSpecifier(this.getQualifier(typeName), this.unQualify(typeName));
    }

    // Builds a TypeSpecifier from the given list of TypeRefComponents
    private TypeSpecifier buildTypeSpecifier(String modelName, TypeRefComponent typeRef) {
        try {
            if (typeRef != null && typeRef.getProfile() != null && typeRef.getProfile().size() != 0) {
                List<CanonicalType> canonicalTypeRefs = typeRef.getProfile();
                if (canonicalTypeRefs.size() == 1) {
                    return this.buildTypeSpecifier(this.resolveTypeName(canonicalTypeRefs.get(0).asStringValue()));
                } else if (canonicalTypeRefs.size() > 1) {
                    ChoiceTypeSpecifier cts = new ChoiceTypeSpecifier();
                    for (CanonicalType canonicalType : canonicalTypeRefs) {
                        if (canonicalType != null) {
                            cts.withChoice(
                                    this.buildTypeSpecifier(this.resolveTypeName(canonicalType.asStringValue())));
                        }
                    }
                    return cts;
                } else
                    return null;
            } else {
                if (this.settings.useCQLPrimitives && typeRef != null
                        && this.settings.cqlTypeMappings.get(modelName + "." + typeRef.getCode()) != null) {
                    String typeName = this.settings.cqlTypeMappings.get(modelName + "." + typeRef.getCode());
                    return this.buildTypeSpecifier(typeName);
                } else {
                    return this.buildTypeSpecifier(modelName,
                            typeRef != null && typeRef.hasCode() ? typeRef.getCode() : null);
                }
            }
        } catch (Exception e) {
            System.out.println("Error building type specifier for " + modelName + "."
                    + (typeRef != null ? typeRef.getCode() : "<No Type>") + ": " + e.getMessage());
            return null;
        }
    }

    private TypeSpecifier buildTypeSpecifier(String modelName, List<TypeRefComponent> typeReferencRefComponents) {

        if (typeReferencRefComponents == null) {
            return buildTypeSpecifier(modelName, (TypeRefComponent) null);
        } else {
            List<TypeSpecifier> specifiers = typeReferencRefComponents.stream()
                    .map(x -> this.buildTypeSpecifier(modelName, x)).filter(distinctByKey(x -> x.toString()))
                    .collect(Collectors.toList());

            if (specifiers.size() == 1) {
                return specifiers.get(0);
            } else if (specifiers.size() > 1) {
                ChoiceTypeSpecifier cts = new ChoiceTypeSpecifier();
                return cts.withChoice(specifiers);
            } else {
                return null;
            }
        }
    }

    // Gets the type specifier for the given class info element
    protected TypeSpecifier getTypeSpecifier(ClassInfoElement classInfoElement) {
        if (classInfoElement.getElementType() != null) {
            return this.buildTypeSpecifier(this.getQualifier(classInfoElement.getElementType()),
                    this.unQualify(classInfoElement.getElementType()));
        } else if (classInfoElement.getElementTypeSpecifier() != null) {
            if (classInfoElement.getElementTypeSpecifier() instanceof ListTypeSpecifier) {
                ListTypeSpecifier lts = (ListTypeSpecifier) classInfoElement.getElementTypeSpecifier();
                if (lts.getElementType() != null) {
                    return this.buildTypeSpecifier(this.getQualifier(lts.getElementType()),
                            this.unQualify(lts.getElementType()));
                } else {
                    return lts.getElementTypeSpecifier();
                }
            }
        }

        return null;
    }

    // Builds the type specifier for the given element
    private TypeSpecifier buildElementTypeSpecifier(String modelName, String root, ElementDefinition ed,
            List<ElementDefinition> eds, ElementDefinition topLevelEd, AtomicReference<Integer> index) {
        String typeCode = this.typeCode(ed);
        String typeName;
        //This is needed to pickup any Extensions that are defined by a profile in the type of the Element Definition
        ElementDefinition nextEd = (index.get() + 1 < eds.size()) ? eds.get(index.get() + 1) : null;
        if (typeCode != null && typeCode.equals("Extension") && ed.getId().contains(":")
                && ed.getType().get(0).hasProfile() && nextEd != null && !nextEd.getId().startsWith(ed.getId())) {
            List<CanonicalType> extensionProfile = ed.getType().get(0).getProfile();
            try {
                if (extensionProfile.size() == 1) {
                    //set targetPath here
                    typeName = capitalize(unQualify(getTail(extensionProfile.get(0).asStringValue())));
                    if (!this.typeInfos.containsKey(this.getTypeName(modelName, typeName))) {

                        List<ClassInfoElement> elements = new ArrayList<>();
                        ClassInfoElement cie = new ClassInfoElement();
                        cie.setName("value");
                        cie.setElementType("System.String");

                        elements.add(cie);

                        ClassInfo info = new ClassInfo().withName(typeName).withNamespace(modelName).withLabel(null).withBaseType(modelName + ".Element")
                                .withRetrievable(false).withElement(elements).withPrimaryCodePath(null);

                        this.typeInfos.put(this.getTypeName(modelName, typeName), info);

                    }

                    NamedTypeSpecifier nts = new NamedTypeSpecifier();
                    nts.setModelName(modelName);
                    nts.setName(typeName);

                    return nts;
                }
                else if (extensionProfile.size() > 1) {
                    ChoiceTypeSpecifier cts = new ChoiceTypeSpecifier();
                    for (CanonicalType canonicalType : extensionProfile) {
                        if (canonicalType != null) {
                            cts.withChoice(
                                    this.buildTypeSpecifier(this.resolveTypeName(canonicalType.asStringValue())));
                        }
                    }

                    return cts;
                }
                else {
                    return null;
                }
            }
            catch (Exception e) {
                System.out.println("Error building type specifier for " + modelName + "."
                    + ed.getId() + ": " + e.getMessage());
                return null;
            }
        }
        else if (typeCode != null && typeCode.equals("code") && ed.hasBinding()
                && ed.getBinding().getStrength() == BindingStrength.REQUIRED) {
            Extension bindingExtension = this.extension(ed.getBinding(), "http://hl7.org/fhir/StructureDefinition/elementdefinition-bindingName");
            if (bindingExtension != null) {
                typeName = capitalizePath(((StringType) (bindingExtension.getValue())).getValue());
            }

            else {
                /* BTR -> This shouldn't be necessary. If it turns out to be, document the reason and clean up this code...
                if (needsTopLevelSD && topLevelEd != null) {
                    Extension topLevelBindingExtension = this.extension(topLevelEd.getBinding(), "http://hl7.org/fhir/StructureDefinition/elementdefinition-bindingName");
                    if (topLevelBindingExtension != null) {
                        typeName = capitalizePath((StringType) (topLevelBindingExtension.getValue())).getValue();
                    }
                    else {
                        typeName = null;
                    }
                }
                else {
                */
                    TypeSpecifier ts = this.buildTypeSpecifier(modelName, ed.hasType() ? ed.getType() : null);
                    if (ts instanceof NamedTypeSpecifier && ((NamedTypeSpecifier) ts).getName() == null) {
                        String tn = this.getTypeName(modelName, root);
                        if (this.settings.primitiveTypeMappings.containsKey(tn)) {
                            ts = this.buildTypeSpecifier(this.settings.primitiveTypeMappings.get(this.getTypeName(modelName, root)));
                        } else {
                            ts = null;
                        }
                    }
                    return ts;
                //}
            }

            if (!this.typeInfos.containsKey(this.getTypeName(modelName, typeName))) {
                //set targetPath here
                List<ClassInfoElement> elements = new ArrayList<>();
                ClassInfoElement cie = new ClassInfoElement();
                cie.setName("value");
                cie.setElementType("System.String");

                elements.add(cie);

                ClassInfo info = new ClassInfo().withName(typeName).withNamespace(modelName).withLabel(null).withBaseType(modelName + ".Element")
                        .withRetrievable(false).withElement(elements).withPrimaryCodePath(null);
                        
                this.typeInfos.put(this.getTypeName(modelName, typeName), info);

            }

            NamedTypeSpecifier nts = new NamedTypeSpecifier();
            nts.setModelName(modelName);
            nts.setName(typeName);

            return nts;
        }
        else {
            TypeSpecifier ts = this.buildTypeSpecifier(modelName, ed.hasType() ? ed.getType() : null);
	        if (ts instanceof NamedTypeSpecifier && ((NamedTypeSpecifier) ts).getName() == null) {
	            String tn = this.getTypeName(modelName, root);
	            if (this.settings.primitiveTypeMappings.containsKey(tn)) {
	                ts = this.buildTypeSpecifier(this.settings.primitiveTypeMappings.get(this.getTypeName(modelName, root)));
	            } else {
	                ts = null;
	            }
	        }
	        return ts;
	    }
    }

    // Builds a ClassInfoElement for the given ElementDefinition
    // This method assumes the given element is not a structure
    private ClassInfoElement buildClassInfoElement(String root, ElementDefinition ed, ElementDefinition structureEd,
            TypeSpecifier typeSpecifier, String modelName) throws Exception {
        if (ed.getContentReference() != null) {
            NamedTypeSpecifier nts = new NamedTypeSpecifier();
            nts.setName(ed.getContentReference());
            typeSpecifier = nts;
        }

        if ( !needsTopLevelSD ) {
            if ( ed.hasBase() && ed.getBase().hasPath() && !ed.getBase().getPath().startsWith(root) ) {
                return null;
            }
        } else {

            if ( ed.hasBase() && ed.getBase().hasPath() && ed.getId().contains(":") ) {
                String[] elementPathSplitByExtensions = ed.getId().split(":");
                if (elementPathSplitByExtensions[elementPathSplitByExtensions.length - 1].contains(".")) {
                    // This is needed for cases when there is an extension or constraint that then
                    // has an element
                    String[] elementPathSplit = ed.getId()
                            .split(ed.getId().substring(ed.getId().lastIndexOf(":"), ed.getId().lastIndexOf(".")));
                    String elementPath = elementPathSplit[0] + elementPathSplit[1];
                    if ( !elementPath.contains(ed.getBase().getPath().toLowerCase()) && !ed.getBase().getPath().contains("value[x]")) {
                        return null;
                    }
                } else if ( elementPathSplitByExtensions[elementPathSplitByExtensions.length - 1].contains("-")) {
                    // This is needed for cases when there is an extension or constraint that then
                    // has an element
                    String[] elementPathSplit = ed.getId()
                            .split(ed.getId().substring(ed.getId().lastIndexOf(":"), ed.getId().lastIndexOf("-")));
                    String elementPath = elementPathSplit[0] + elementPathSplit[elementPathSplit.length - 1];
                    if ( !elementPath.contains(ed.getBase().getPath().toLowerCase())) {
                        return null;
                    }
                } else {
                    String[] elementPathSplit = ed.getId().split(ed.getId().substring(ed.getId().lastIndexOf(":")));
                    String elementPath = elementPathSplit[0];
                    if ( !elementPath.contains(ed.getBase().getPath()) && !elementPath.contains("extension")) {
                        return null;
                    }
                }

            } else if ( !ed.getId().contains(ed.getBase().getPath())) {
                return null;
            }
        }

        // TODO: These code paths look identical to me...
        if (structureEd == null) {
            if (ed.getMax() != null && (ed.getMax().equals("*") || (this.asInteger(ed.getMax()) > 1))) {
                ListTypeSpecifier lts = new ListTypeSpecifier();
                if (typeSpecifier instanceof NamedTypeSpecifier) {
                    lts.setElementType(this.getTypeName((NamedTypeSpecifier) typeSpecifier));
                } else {
                    lts.setElementTypeSpecifier(typeSpecifier);
                }

                typeSpecifier = lts;
            }

            String name = ed.getSliceName() != null ? ed.getSliceName()
                    : this.stripChoice(this.stripPath(ed.getPath()));

            ClassInfoElement cie = new ClassInfoElement();
            cie.setName(name);
            if (typeSpecifier instanceof NamedTypeSpecifier) {
                cie.setElementType(this.getTypeName((NamedTypeSpecifier) typeSpecifier));
            } else {
                cie.setElementTypeSpecifier(typeSpecifier);
            }

            return cie;
        } else {
            if (ed.getMax() != null && (ed.getMax().equals("*") || (this.asInteger(ed.getMax()) > 1))) {
                ListTypeSpecifier lts = new ListTypeSpecifier();
                if (typeSpecifier instanceof NamedTypeSpecifier) {
                    lts.setElementType(this.capitalizePath(this.getTypeName((NamedTypeSpecifier) typeSpecifier)));
                } else {
                    lts.setElementTypeSpecifier(typeSpecifier);
                }

                typeSpecifier = lts;
            }

            String name = ed.getSliceName() != null ? ed.getSliceName()
                    : this.stripChoice(this.stripPath(ed.getPath()));

            ClassInfoElement cie = new ClassInfoElement();
            cie.setName(name);
            if (typeSpecifier instanceof NamedTypeSpecifier) {
                cie.setElementType(
                        this.capitalizePath(this.getTypeName((NamedTypeSpecifier) typeSpecifier), modelName));
            } else {
                cie.setElementTypeSpecifier(typeSpecifier);
            }

            return cie;
        }
    }

    private boolean checkContinuationById(String extensionPath, ElementDefinition e) {
        return e.getId().startsWith(extensionPath) && e.getId().split(extensionPath).length > 1
                && (e.getId().split(extensionPath)[1].startsWith(".") || e.getId().split(extensionPath)[1].startsWith("-")) && !e.getId().equals(extensionPath);
    }

    private boolean checkContinuationByPath(String path, ElementDefinition e) {
        return e.getPath().startsWith(path) && e.getPath().split(path).length > 1
                && (e.getPath().split(path)[1].startsWith(".") || e.getPath().split(path)[1].startsWith("-")) && !e.getPath().equals(path);
    }

private boolean isNextAContinuationOfElementExtension(String path, ElementDefinition e) {
    if(e.getId().contains(":"))
    {
        if (path.contains(":"))
        {
            return checkContinuationById(path, e);
        }
        else return (path.endsWith("extension")) ? false : true;
    }
     else
        return checkContinuationByPath(path, e);
}

private boolean isNextAContinuationOfElement(String path, ElementDefinition e) {
    if(this.isExtension(e))
    {
        return isNextAContinuationOfElementExtension(path, e);
    }
    else if (path.contains(":"))
    {
        return checkContinuationById(path, e);
    }
    else {
        return checkContinuationByPath(path, e);
    }
}


    // Visits the given element definition and returns a ClassInfoElement. If the
    // element is a BackboneElement
    // the visit will create an appropriate ClassInfo and record it in the TypeInfos
    // table
    // On return, index will be updated to the index of the next element to be
    // processed
    // This visit should not be used on the root element of a structure definition
    private ClassInfoElement visitElementDefinition(String modelName, String root, List<ElementDefinition> eds, List<ElementDefinition> topLevelEds,
            String structureRoot, List<ElementDefinition> structureEds, AtomicReference<Integer> index)
            throws Exception {
        ElementDefinition ed = eds.get(index.get());
        ElementDefinition topLevelEd = null; 
        if (needsTopLevelSD)
        {
            for (ElementDefinition elementDefinition : topLevelEds)
            {
                    if (elementDefinition.getId().equals(ed.getId()))
                    {
                        topLevelEd = elementDefinition;
                        break;
                    }
            }
        }
        String path;
        if(isExtension(ed) || ed.getId().contains(":"))
        {
            path = ed.getId();
        }
        else path = ed.getPath();

        TypeSpecifier typeSpecifier = this.buildElementTypeSpecifier(modelName, root, ed, eds, topLevelEd, index);

        String typeCode = this.typeCode(ed);
        StructureDefinition typeDefinition;
        if(this.settings.useCQLPrimitives && !this.settings.primitiveTypeMappings.containsKey("QUICK." + typeCode))
        {
            typeDefinition = structureDefinitions.get(typeCode);
        }
        else if (!this.settings.useCQLPrimitives){
            typeDefinition = structureDefinitions.get(typeCode);
        }
        else typeDefinition = null;
            
        List<ElementDefinition> typeEds;
        if (typeCode != null && typeCode.equals("ComplexType") && !this.isBackboneElement(typeDefinition)) {
            typeEds = typeDefinition.getSnapshot().getElement();
        } else {
            typeEds = structureEds;
        }

        String typeRoot;
        if (typeCode != null && typeCode.equals("ComplexType") && !this.isBackboneElement(typeDefinition)) {
            typeRoot = getTail(typeDefinition.getId());
        } else {
            typeRoot = structureRoot;
        }

        index.set(index.get() + 1);
        List<ClassInfoElement> elements = new ArrayList<>();
        while (index.get() < eds.size()) {
            ElementDefinition e = eds.get(index.get());
            // else 
            if (isNextAContinuationOfElement(path, e)) {
                ClassInfoElement cie = this.visitElementDefinition(modelName, root, eds, topLevelEds, typeRoot, structureEds, index);
                if (cie != null && !(cie.getElementType() == null && cie.getElementTypeSpecifier() == null)) {
                    elements.add(cie);
                }

            } else {
                break;
            }
        }

        if (elements.size() > 0) {
            if(elements.size() == 1) {
                if(this.settings.primitiveTypeMappings.containsKey(elements.get(0).getElementType())) {
                    //set targetPath here
                }
            }
            if (typeDefinition != null && isBackboneElement(typeDefinition)) {
                //String typeName = this.getComponentTypeName(path);
                String typeName = this.capitalizePath(path);

                
                ClassInfo componentClassInfo = new ClassInfo().withNamespace(modelName).withName(typeName).withLabel(null)
                        .withBaseType(modelName + ".BackboneElement").withRetrievable(false).withElement(elements)
                        .withPrimaryCodePath(null);

                this.typeInfos.put(this.getTypeName(modelName, typeName), componentClassInfo);

                typeSpecifier = this.buildTypeSpecifier(modelName, typeName);

            }
            else if (typeDefinition != null && isExtension(typeDefinition)) {
                // If this is an extension, the elements will be constraints on the existing
                // elements of an extension (i.e. url and value)
                // Use the type of the value element
                String extensionTypeName;
                if(elements.size() == 1) {
                    String primitiveTypeName = "QUICK." + unQualify(elements.get(0).getElementType()).toLowerCase();
                    if(this.settings.primitiveTypeMappings.containsKey(primitiveTypeName)) {
                        //set targetPath here
                    }
                }
                if((ed.getType().size() == 1))
                {
                    List<CanonicalType> canonicalTypeRefs = ed.getType().get(0).getProfile();
                    if (canonicalTypeRefs.size() == 1) {
                        extensionTypeName = this.resolveTypeName(canonicalTypeRefs.get(0).asStringValue());
                    }
                    else extensionTypeName = path;
                }
                else extensionTypeName = path;
                    ClassInfo componentClassInfo = new ClassInfo().withNamespace(modelName).withName(this.unQualify(this.capitalizePath(extensionTypeName))).withLabel(null)
                        .withBaseType(modelName + ".BackboneElement").withRetrievable(false).withElement(elements)
                        .withPrimaryCodePath(null);

                this.typeInfos.put(this.getTypeName(modelName, path), componentClassInfo);
            } else {
                // element has children that are being ignored.
            }
        }

        ElementDefinition typeEd = this.elementForPath(typeEds, ed.getPath());

        return this.buildClassInfoElement(root, ed, typeEd, typeSpecifier, modelName);
    }

    // Given a StructureDefinition, creates a ClassInfo to represent it
    // This approach uses the base type to guide the walk, which requires navigating
    // the derived profiles
    private ClassInfo buildClassInfo(String modelName, StructureDefinition sd, StructureDefinition topLevelSd) throws Exception {
        if (modelName == null) {
            modelName = this.resolveModelName(sd.getUrl());
        }
        //Make sure the Id in unQualified and Capitalized
        String typeName = (unQualify(getTail(sd.getId())) == null) ?  getTail(sd.getId()) : capitalize(unQualify(getTail(sd.getId())));
        AtomicReference<Integer> index = new AtomicReference<Integer>(1);
        List<ClassInfoElement> elements = new ArrayList<>();
        String path = sd.getType(); // Type is used to navigate the elements, regardless of the baseDefinition
        List<ElementDefinition> topLevelEds = (!needsTopLevelSD) ? null : (topLevelSd.getKind() == StructureDefinitionKind.RESOURCE && topLevelSd.getDerivation() == StructureDefinition.TypeDerivationRule.CONSTRAINT) || !typeName.equals(path)
                ? topLevelSd.getSnapshot().getElement()
                : topLevelSd.getSnapshot().getElement();
        List<ElementDefinition> eds = (sd.getKind() == StructureDefinitionKind.RESOURCE && sd.getDerivation() == StructureDefinition.TypeDerivationRule.CONSTRAINT) || !typeName.equals(path)
                ? sd.getSnapshot().getElement()
                : sd.getSnapshot().getElement();
        
        StructureDefinition structure = null;
        if (!typeName.equals(path)) {
            structure = structureDefinitions.get(path);
        }
        //else if ()

        List<ElementDefinition> structureEds = null;
        if (structure != null) {
            structureEds = structure.getSnapshot().getElement();
        }

        while (index.get() < eds.size()) {
            ElementDefinition e = eds.get(index.get());
            if(this.isExtension(e))
            {
                if (isNextAContinuationOfElement(path, e)) {
                ClassInfoElement cie = this.visitElementDefinition(modelName, typeName, eds, topLevelEds, structure == null ? null : getTail(structure.getId()),
                        eds, index);
                if (cie != null && !(cie.getElementType() == null && cie.getElementTypeSpecifier() == null)) {
                    elements.add(cie);
                }

                }
            }
            else if (isNextAContinuationOfElement(path, e)) {
                ClassInfoElement cie = this.visitElementDefinition(modelName, typeName, eds, topLevelEds, structure == null ? null : getTail(structure.getId()),
                        structureEds, index);
                if (cie != null && !(cie.getElementType() == null && cie.getElementTypeSpecifier() == null)) {
                    elements.add(cie);
                }

            } else {
                break;
            }
        }

        System.out.println("Building ClassInfo for " + typeName);
        String baseDefinition = (!needsTopLevelSD) ? sd.getBaseDefinition() : topLevelSd.getBaseDefinition();

        ClassInfo info = new ClassInfo().withName(typeName).withNamespace(modelName).withLabel(this.getLabel(sd))
                .withBaseType(this.resolveTypeName(baseDefinition))
                .withRetrievable(sd.getKind() == StructureDefinitionKind.RESOURCE).withElement(elements)
                .withPrimaryCodePath(this.primaryCodePath(elements, typeName));

        this.typeInfos.put(this.getTypeName(modelName, typeName), info);

        return info;
    }

    private StructureDefinition getBaseDefinitionStructureDef(String model, StructureDefinition sd) {
        String baseSd = (sd.getBaseDefinition() == null) ? null : getTail(sd.getBaseDefinition());
                    if (baseSd != null && !baseSd.equals("ElementDefinition") && !baseSd.equals("Element") 
                    && !baseSd.equals("BackboneElement") && !baseSd.equals("Resource") && !baseSd.equals("DomainResource")
                    && !this.settings.primitiveTypeMappings.containsKey(model + "." + baseSd)
                    && this.settings.useCQLPrimitives) {
                        return getBaseDefinitionStructureDef(model, structureDefinitions.get(baseSd));
                    }
                    else return sd;

    }
    protected void buildFor(String model, Predicate<StructureDefinition> predicate) {
        for (StructureDefinition sd : structureDefinitions.values()) {
            if (predicate.test(sd)) {
                try {
                    //if StructureDefinition type is constrained/specialized from something other than a baseResource or baseType
                    StructureDefinition topLevelSd = getBaseDefinitionStructureDef(model, sd);
                    if(topLevelSd.getId() != sd.getId())
                    {
                        needsTopLevelSD = true;
                        this.buildClassInfo(model, sd, topLevelSd);
                    }
                    else {
                        needsTopLevelSD = false;
                        this.buildClassInfo(model, sd, null);
                    }
                } catch (Exception e) {
                    System.out.println("Error building ClassInfo for: " + sd.getId() + " - " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    public Map<String, TypeInfo> getTypeInfos() {
        return this.typeInfos;
    }

}