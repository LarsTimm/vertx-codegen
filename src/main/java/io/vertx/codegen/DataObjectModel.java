package io.vertx.codegen;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.doc.Doc;
import io.vertx.core.json.JsonObject;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class DataObjectModel implements Model {

  private final Elements elementUtils;
  private final Types typeUtils;
  private final Doc.Factory docFactory;
  private final TypeInfo.Factory typeFactory;
  private final TypeElement modelElt;
  private boolean processed = false;
  private boolean concrete;
  private boolean isClass;
  private boolean generateConverter;
  private boolean inheritConverter;
  private final Map<String, PropertyInfo> propertyMap = new LinkedHashMap<>();
  private final Set<TypeInfo.Class> superTypes = new LinkedHashSet<>();
  private TypeInfo.Class superType;
  private final Set<TypeInfo.Class> abstractSuperTypes = new LinkedHashSet<>();
  private final Set<TypeInfo.Class> importedTypes = new LinkedHashSet<>();
  private TypeInfo.Class type;
  private Doc doc;

  public DataObjectModel(Elements elementUtils, Types typeUtils, TypeElement modelElt, Messager messager) {
    this.elementUtils = elementUtils;
    this.typeUtils = typeUtils;
    this.typeFactory = new TypeInfo.Factory(elementUtils, typeUtils);
    this.docFactory = new Doc.Factory(messager, elementUtils, typeUtils, typeFactory, modelElt);
    this.modelElt = modelElt;
  }

  @Override
  public String getKind() {
    return "dataObject";
  }

  @Override
  public Element getElement() {
    return modelElt;
  }

  @Override
  public String getFqn() {
    return type.getName();
  }

  public Doc getDoc() {
    return doc;
  }

  public boolean isAbstract() {
    return !concrete;
  }

  public boolean isConcrete() {
    return concrete;
  }

  public Set<TypeInfo.Class> getImportedTypes() {
    return importedTypes;
  }

  public Map<String, PropertyInfo> getPropertyMap() {
    return propertyMap;
  }

  public TypeInfo.Class getSuperType() {
    return superType;
  }

  public Set<TypeInfo.Class> getAbstractSuperTypes() {
    return abstractSuperTypes;
  }

  public Set<TypeInfo.Class> getSuperTypes() {
    return superTypes;
  }

  public ModuleInfo getModule() {
    return type.getRaw().getModule();
  }

  public boolean isClass() {
    return isClass;
  }

  public boolean getGenerateConverter() {
    return generateConverter;
  }

  public boolean getInheritConverter() {
    return inheritConverter;
  }

  @Override
  public Map<String, Object> getVars() {
    HashMap<String, Object> vars = new HashMap<>();
    vars.put("type", type);
    vars.put("doc", doc);
    vars.put("generateConverter", generateConverter);
    vars.put("inheritConverter", inheritConverter);
    vars.put("concrete", concrete);
    vars.put("properties", propertyMap.values());
    vars.put("importedTypes", importedTypes);
    vars.put("superTypes", superTypes);
    vars.put("superType", superType);
    vars.put("abstractSuperTypes", abstractSuperTypes);
    vars.putAll(ClassKind.vars());
    vars.putAll(MethodKind.vars());
    vars.putAll(Case.vars());
    return vars;
  }

  boolean process() {
    if (!processed) {
      if (modelElt.getKind() == ElementKind.INTERFACE || modelElt.getKind() == ElementKind.CLASS) {
        traverse();
        processImportedTypes();
        processed = true;
        return true;
      } else {
        throw new GenException(modelElt, "Data object " + modelElt + " must be an interface or a class");
      }
    }
    return false;
  }

  private void traverse() {
    DataObject ann = modelElt.getAnnotation(DataObject.class);
    this.generateConverter = ann.generateConverter();
    this.inheritConverter = ann.inheritConverter();
    this.isClass = modelElt.getKind() == ElementKind.CLASS;
    this.concrete = isClass && !modelElt.getModifiers().contains(Modifier.ABSTRACT);
    try {
      this.type = (TypeInfo.Class) typeFactory.create(modelElt.asType());
    } catch (ClassCastException e) {
      throw new GenException(modelElt, "Data object must be a plain java class with no type parameters");
    }
    doc = docFactory.createDoc(modelElt);

    if (getModule() == null) {
      throw new GenException(modelElt, "Data object must have an ancestor package annotated with @ModuleGen");
    }

    modelElt.getInterfaces().stream()
      .filter(superTM -> superTM instanceof DeclaredType && ((DeclaredType) superTM).asElement().getAnnotation(DataObject.class) != null)
      .map(e -> (TypeInfo.Class) typeFactory.create(e)).forEach(abstractSuperTypes::add);

    superTypes.addAll(abstractSuperTypes);

    TypeMirror superClass = modelElt.getSuperclass();
    if (superClass instanceof DeclaredType && ((DeclaredType) superClass).asElement().getAnnotation(DataObject.class) != null) {
      superType = (TypeInfo.Class) typeFactory.create(superClass);
      superTypes.add(superType);
    }

    int result = 0;
    for (Element enclosedElt : elementUtils.getAllMembers(modelElt)) {
      switch (enclosedElt.getKind()) {
        case CONSTRUCTOR:
          ExecutableElement constrElt = (ExecutableElement) enclosedElt;
          result |= processConstructor(constrElt);
          break;
        case METHOD: {
          ExecutableElement methodElt = (ExecutableElement) enclosedElt;
          if (methodElt.getAnnotation(GenIgnore.class) == null) {
            processMethod(methodElt);
          }
          break;
        }
      }
    }
    boolean hasDefaultConstructor = (result & 2) == 2;
    boolean hasCopyConstructor = (result & 4) == 4;
    boolean hasJsonConstructor = (result & 8) == 8;

    if (concrete && !hasDefaultConstructor) {
      throw new GenException(modelElt, "Data object " + modelElt + " class does not have a default constructor");
    }
    if (concrete && !hasCopyConstructor) {
      throw new GenException(modelElt, "Data object " + modelElt + " class does not have a constructor " + modelElt.getSimpleName() + "(" + modelElt.getSimpleName() + ") ");
    }
    if (concrete && !hasJsonConstructor) {
      throw new GenException(modelElt, "Data object " + modelElt + " class does not have a constructor " + modelElt.getSimpleName() + "(" + JsonObject.class.getSimpleName() + ")");
    }

    // Sort the properties so we do have a consistent order
    ArrayList<PropertyInfo> props = new ArrayList<>(propertyMap.values());
    Collections.sort(props, (p1, p2) -> p1.name.compareTo(p2.name));
    propertyMap.clear();
    props.forEach(prop -> propertyMap.put(prop.name, prop));
  }

  private void processImportedTypes() {
    for (PropertyInfo property : propertyMap.values()) {
      property.type.collectImports(importedTypes);
    }
    importedTypes.addAll(superTypes.stream().collect(toList()));
    for (Iterator<TypeInfo.Class> i = importedTypes.iterator();i.hasNext();) {
      TypeInfo.Class importedType = i.next();
      if (importedType.getPackageName().equals(type.getPackageName())) {
        i.remove();
      }
    }
  }

  private int processConstructor(ExecutableElement constrElt) {
    if (constrElt.getModifiers().contains(Modifier.PUBLIC)) {
      Element ownerElt = constrElt.getEnclosingElement();
      if (ownerElt.equals(modelElt)) {
        List<? extends VariableElement> parameters = constrElt.getParameters();
        int size = parameters.size();
        if (size == 0) {
          return 2;
        } else {
          if (size == 1) {
            TypeInfo ti = typeFactory.create(parameters.get(0).asType());
            if (ti instanceof TypeInfo.Class) {
              TypeInfo.Class cl = (TypeInfo.Class) ti;
              if (cl.name.equals(getFqn())) {
                return 4;
              } else if (cl.getKind() == ClassKind.JSON_OBJECT) {
                return 8;
              }
            }
          }
        }
      }
    }

    return 0;
  }

  private void processMethod(ExecutableElement methodElt) {
    String mutatorMethod = methodElt.getSimpleName().toString();
    if (mutatorMethod.length() > 3) {
      String prefix = mutatorMethod.substring(0, 3);
      String abc = mutatorMethod.substring(3);
      String name = Helper.normalizePropertyName(abc);
      List<? extends VariableElement> parameters = methodElt.getParameters();
      switch (prefix) {
        case "add":
        case "set": {
          if (parameters.size() != 1) {
            return;
          }
          VariableElement paramElt = parameters.get(0);
          TypeMirror propTypeMirror = paramElt.asType();
          TypeInfo propType = typeFactory.create(propTypeMirror);
          boolean array;
          boolean adder;
          if ("add".equals(prefix)) {
            if (name.endsWith("s")) {
              throw new GenException(methodElt, "Option adder name must not terminate with 's' char");
            } else {
              name += "s";
            }
            array = true;
            adder = true;
          } else {
            adder = false;
            if (propType.getKind() == ClassKind.LIST) {
              propType = ((TypeInfo.Parameterized) propType).getArgs().get(0);
              array = true;
            } else {
              array = false;
            }
          }

          boolean jsonifiable;
          switch (propType.getKind()) {
            case PRIMITIVE:
            case BOXED_PRIMITIVE:
            case STRING:
            case API:
            case JSON_OBJECT:
            case JSON_ARRAY:
            case ENUM:
              jsonifiable = true;
              break;
            case DATA_OBJECT:
              TypeMirror jsonType = elementUtils.getTypeElement("io.vertx.core.json.JsonObject").asType();
              Element propTypeElt = typeUtils.asElement(propTypeMirror);
              jsonifiable = propTypeElt.getAnnotation(DataObject.class) == null ||
                  elementUtils.getAllMembers(
                      (TypeElement) propTypeElt).stream().
                      flatMap(Helper.FILTER_METHOD).
                      filter(exeElt -> exeElt.getSimpleName().toString().equals("toJson") && typeUtils.isSameType(jsonType, exeElt.getReturnType())).
                      count() > 0;
              break;
            default:
              return;
          }

          String readerMethod;
          if ((propType.getName().equals("boolean") || propType.getName().equals("java.lang.Boolean")) && !array) {
            readerMethod = "is" + abc;
          } else {
            readerMethod = "get" + abc + (adder ? "s" : "");
          }

          TypeMirror readerType;
          if (adder) {
            TypeElement listType = elementUtils.getTypeElement("java.util.List");
            TypeMirror eltType = propTypeMirror;
            if (eltType instanceof PrimitiveType) {
              eltType = typeUtils.boxedClass((PrimitiveType) eltType).asType();
            }
            readerType = typeUtils.getDeclaredType(listType, eltType);
          } else {
            readerType = propTypeMirror;
          }
          boolean hasReader = elementUtils.getAllMembers(modelElt).
              stream().
              flatMap(Helper.FILTER_METHOD).
              filter(elt -> elt.getSimpleName().toString().equals(readerMethod)).
              filter(elt -> typeUtils.isSameType(elt.getReturnType(), readerType)).
              count() > 0;

          // A stream that list all overriden methods from super types
          // the boolean control whether or not we want to filter only annotated
          // data objects
          Function<Boolean, Stream<ExecutableElement>> overridenMeths = (annotated) -> {
            Set<DeclaredType> ancestorTypes = Helper.resolveAncestorTypes(modelElt, true, true);
            return ancestorTypes.
                stream().
                map(DeclaredType::asElement).
                filter(elt -> !annotated || elt.getAnnotation(DataObject.class) != null).
                flatMap(Helper.cast(TypeElement.class)).
                flatMap(elt -> elementUtils.getAllMembers(elt).stream()).
                flatMap(Helper.instanceOf(ExecutableElement.class)).
                filter(executableElt -> executableElt.getKind() == ElementKind.METHOD && elementUtils.overrides(methodElt, executableElt, modelElt));
          };

          boolean declared;
          Element ownerElt = methodElt.getEnclosingElement();
          if (ownerElt.equals(modelElt)) {
            Object[] arr = overridenMeths.apply(true).limit(1).filter(elt -> !elt.getModifiers().contains(Modifier.ABSTRACT)).toArray();
            // Handle the case where this methods overrides from another data object
            declared = arr.length == 0;
          } else {
            declared = ownerElt.getAnnotation(DataObject.class) == null;
          }

          Doc doc = docFactory.createDoc(methodElt);
          if (doc == null) {
            Optional<Doc> first = overridenMeths.apply(false).
                map(docFactory::createDoc).
                filter(d -> d != null).
                findFirst();
            doc = first.orElse(null);
          }

          PropertyInfo property = new PropertyInfo(declared, name, doc, propType, mutatorMethod, hasReader ? readerMethod : null, array, adder, jsonifiable);
          propertyMap.put(property.name, property);
        }
      }
    }
  }



}
