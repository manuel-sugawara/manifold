/*
 * Copyright (c) 2021 - Manifold Systems LLC
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

package manifold.ext.props;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.util.*;
import manifold.ext.props.rt.api.*;
import manifold.internal.javac.IDynamicJdk;
import manifold.rt.api.util.ManStringUtil;
import manifold.util.JreUtil;
import manifold.util.ReflectUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.sun.tools.javac.code.TypeTag.CLASS;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.reflect.Modifier.*;
import static java.lang.reflect.Modifier.PRIVATE;
import static manifold.ext.props.Util.*;

class PropertyInference
{
  private final Consumer<VarSymbol> _backingFieldConsumer;
  private final Supplier<Context> _contextSupplier;
  private final Supplier<CompilationUnitTree> _compilationUnitSupplier;
    
  PropertyInference( Consumer<VarSymbol> backingFieldConsumer,
                     Supplier<Context> contextSupplier,
                     Supplier<CompilationUnitTree> compilationUnitSupplier )
  {
    _backingFieldConsumer = backingFieldConsumer;
    _contextSupplier = contextSupplier;
    _compilationUnitSupplier = compilationUnitSupplier;
  }

  private Context context()
  {
    return _contextSupplier.get();
  }

  void inferProperties( ClassSymbol classSym )
  {
    Map<String, Set<PropAttrs>> fromGetter = new HashMap<>();
    Map<String, Set<PropAttrs>> fromSetter = new HashMap<>();
    for( Symbol sym : IDynamicJdk.instance().getMembers( classSym, false ) )
    {
      if( sym instanceof Symbol.MethodSymbol )
      {
        gatherCandidates( (Symbol.MethodSymbol)sym, fromGetter, fromSetter );
      }
    }

    handleVars( fromGetter, fromSetter );
    handleVals( fromGetter, fromSetter );
    handleWos( fromGetter, fromSetter );
  }

  private void gatherCandidates( Symbol.MethodSymbol m, Map<String, Set<PropAttrs>> fromGetter, Map<String, Set<PropAttrs>> fromSetter )
  {
    Attribute.Compound propgenAnno = getAnnotationMirror( m, propgen.class );
    if( propgenAnno != null )
    {
      // already a property
      return;
    }

    PropAttrs derivedFromGetter = derivePropertyNameFromGetter( m );
    if( derivedFromGetter != null )
    {
      fromGetter.computeIfAbsent( derivedFromGetter._name, key -> new HashSet<>() )
        .add( derivedFromGetter );
    }
    PropAttrs derivedFromSetter = derivePropertyNameFromSetter( m );
    if( derivedFromSetter != null )
    {
      fromSetter.computeIfAbsent( derivedFromSetter._name, key -> new HashSet<>() )
        .add( derivedFromSetter );
    }
  }

  private boolean isInherited( Symbol ancestorSym, ClassSymbol origin )
  {
    ClassSymbol ancestorClass = ancestorSym.enclClass();
    if( ancestorClass == origin )
    {
      return true;
    }
    if( ancestorSym.isStatic() && ancestorClass.isInterface() )
    {
      return false;
    }
    if( Modifier.isPublic( (int)ancestorSym.flags_field ) ||
      Modifier.isProtected( (int)ancestorSym.flags_field ) )
    {
      return true;
    }
    if( Modifier.isPrivate( (int)ancestorSym.flags_field ) )
    {
      return ancestorClass.outermostClass() == origin.outermostClass();
    }
    // package-private
    return !Modifier.isPrivate( (int)ancestorSym.flags_field ) &&
      ancestorClass.packge().equals( origin.packge() );
  }

  private void handleVars( Map<String, Set<PropAttrs>> fromGetter, Map<String, Set<PropAttrs>> fromSetter )
  {
    for( Map.Entry<String, Set<PropAttrs>> entry : fromGetter.entrySet() )
    {
      String name = entry.getKey();
      Set<PropAttrs> getters = entry.getValue();
      Set<PropAttrs> setters = fromSetter.get( name );
      if( getters != null && !getters.isEmpty() && setters != null && !setters.isEmpty() )
      {
        Types types = Types.instance( context() );
        outer:
        for( PropAttrs getAttr : getters )
        {
          Type getType = getAttr._type;
          for( PropAttrs setAttr : setters )
          {
            Type setType = setAttr._type;
            if( types.isAssignable( getType, setType ) && getAttr._m.isStatic() == setAttr._m.isStatic() )
            {
              makeVar( getAttr, setAttr );
              break outer;
            }
          }
        }
      }
    }
  }

  private void handleVals( Map<String, Set<PropAttrs>> fromGetter, Map<String, Set<PropAttrs>> fromSetter )
  {
    for( Map.Entry<String, Set<PropAttrs>> entry : fromGetter.entrySet() )
    {
      String name = entry.getKey();
      Set<PropAttrs> getters = entry.getValue();
      if( getters != null && !getters.isEmpty() )
      {
        Set<PropAttrs> setters = fromSetter.get( name );
        if( setters == null || setters.isEmpty() )
        {
          makeVal( getters.iterator().next() );
        }
      }
    }
  }

  private void handleWos( Map<String, Set<PropAttrs>> fromGetter, Map<String, Set<PropAttrs>> fromSetter )
  {
    for( Map.Entry<String, Set<PropAttrs>> entry : fromSetter.entrySet() )
    {
      String name = entry.getKey();
      Set<PropAttrs> setters = entry.getValue();
      if( setters != null && !setters.isEmpty() )
      {
        Set<PropAttrs> getters = fromGetter.get( name );
        if( getters == null || getters.isEmpty() )
        {
          makeWo( setters.iterator().next() );
        }
      }
    }
  }

  private void makeVar( PropAttrs getAttr, PropAttrs setAttr )
  {
    Names names = Names.instance( context() );
    Name fieldName = names.fromString( getAttr._name );
    ClassSymbol classSym = getAttr._m.enclClass();

    Type t = getMoreSpecificType( getAttr._type, setAttr._type );
    int flags = weakest( getAccess( getAttr._m ), getAccess( setAttr._m ) );
    flags |= (getAttr._m.flags_field & STATIC);

    Pair<Integer, VarSymbol> res = handleExistingField( fieldName, t, flags, classSym, var.class );
    if( res == null )
    {
      // existing field found and, if local and compatible, changed access privilege in-place and added @var|val|set
      return;
    }

    // Create and enter the prop field

    flags = res.fst == MAX_VALUE ? flags : weakest( res.fst, flags );
    VarSymbol propField = new VarSymbol( flags, fieldName, t, classSym );

    addField( propField, classSym, var.class );
  }

  private void makeVal( PropAttrs getAttr )
  {
    Names names = Names.instance( context() );
    Name fieldName = names.fromString( getAttr._name );
    ClassSymbol classSym = getAttr._m.enclClass();

    Pair<Integer, VarSymbol> res = handleExistingField( fieldName, getAttr._type, (int)getAttr._m.flags_field, classSym, val.class );
    if( res == null )
    {
      // existing field found and, if local and compatible, changed access privilege in-place and added @var|val|set
      return;
    }

    // Create and enter the prop field

    int flags = res.fst == MAX_VALUE
      ? getAccess( (int)getAttr._m.flags_field )
      : weakest( res.fst, (int)getAttr._m.flags_field );
    flags |= (getAttr._m.flags_field & STATIC);
    VarSymbol propField = new VarSymbol( flags, fieldName, getAttr._type, classSym );

    // if super's field is writable, make this one also writable to allow the setter to be used in assignments
    Class<? extends Annotation> varClass =
      isWritableProperty( res.snd )
        ? var.class
        : val.class;
    addField( propField, classSym, varClass );
  }

  private void makeWo( PropAttrs setAttr )
  {
    Names names = Names.instance( context() );
    Name fieldName = names.fromString( setAttr._name );
    ClassSymbol classSym = setAttr._m.enclClass();

    Pair<Integer, VarSymbol> res = handleExistingField( fieldName, setAttr._type, (int)setAttr._m.flags_field, classSym, set.class );
    if( res == null )
    {
      // existing field found and, if local and compatible, changed access privilege in-place
      return;
    }

    // Create and enter the prop field

    int flags = res.fst == MAX_VALUE
      ? getAccess( (int)setAttr._m.flags_field )
      : weakest( res.fst, (int)setAttr._m.flags_field );
    Type t = setAttr._type;

    flags |= (setAttr._m.flags_field & STATIC);
    VarSymbol propField = new VarSymbol( flags, fieldName, t, classSym );

    // if super's field is readable, make this one also readable to allow the getter to be used
    Class<? extends Annotation> varClass =
      isReadableProperty( res.snd )
        ? var.class
        : set.class;
    addField( propField, classSym, varClass );
  }

  private void addField( VarSymbol propField, ClassSymbol classSym, Class<? extends Annotation> varClass )
  {
    addField( propField, classSym, varClass, -1 );
  }

  private void addField( VarSymbol propField, ClassSymbol classSym, Class<? extends Annotation> varClass, int existingDeclaredAccess )
  {
    Object ctx = _compilationUnitSupplier.get();
    if( JreUtil.isJava9orLater() )
    {
      ctx = ReflectUtil.method( JavacElements.instance( context() ), "getModuleElement", CharSequence.class ).invoke( "manifold.props.rt" );
    }
    ClassSymbol varSym = IDynamicJdk.instance().getTypeElement( context(), ctx, varClass.getTypeName() );
    ClassSymbol autoSym = IDynamicJdk.instance().getTypeElement( context(), ctx, auto.class.getTypeName() );
    if( varSym != null )
    {
      Attribute.Compound varAnno = new Attribute.Compound( varSym.type, List.nil() );
      // tag with @auto to prevent usage within the enclosing class
      Attribute.Compound autoAnno;
      if( existingDeclaredAccess == -1 )
      {
        autoAnno = new Attribute.Compound( autoSym.type, List.nil() );
      }
      else
      {
        // there is an existing field, store its declared access privilege modifier

        Names names = Names.instance( context() );
        Symtab symtab = Symtab.instance( context() );
        Symbol.MethodSymbol declaredAccessMeth = (Symbol.MethodSymbol)IDynamicJdk.instance().getMembersByName(
          autoSym, names.fromString( "declaredAccess" ) ).iterator().next();
        autoAnno = new Attribute.Compound( autoSym.type,
          List.of( new Pair<>( declaredAccessMeth,
            new Attribute.Constant( symtab.intType, existingDeclaredAccess ) ) ) );

        // Also add it to backing fields, since this indicates the field should not be erased (apples to compiled source)
        _backingFieldConsumer.accept( propField );
      }
      // add the @var, @val, @get, @set, etc. annotations
      propField.appendAttributes( List.of( varAnno, autoAnno ) );

      if( classSym != null )
      {
        // reflectively call:  classSym.members_field.enter( propField );
        ReflectUtil.method( ReflectUtil.field( classSym, "members_field" ).get(),
          "enter", Symbol.class ).invoke( propField );
      }
    }
  }

  private Type getMoreSpecificType( Type t1, Type t2 )
  {
    Types types = Types.instance( context() );
    if( types.isSameType( t1, t2 ) )
    {
      return t1;
    }
    return types.isAssignable( t1, t2 ) ? t1 : t2;
  }

  private Pair<Integer, VarSymbol> handleExistingField( Name fieldName, Type t, int flags, ClassSymbol classSym, Class<? extends Annotation> varClass )
  {
    Symbol[] existing = findExistingFieldInAncestry( fieldName, classSym, classSym );
    if( existing != null && existing.length > 0 )
    {
      // a field already exists with this name

      VarSymbol exField = (VarSymbol)existing[0];
      Types types = Types.instance( context() );
      if( types.isAssignable( exField.type, t ) &&
        Modifier.isStatic( (int)exField.flags_field ) == Modifier.isStatic( flags ) && !exField.owner.isInterface() &&
        (!Modifier.isPublic( (int)exField.flags_field ) || isPropertyField( exField )) /* existing public field must always be accessed directly (see keep PropertyProcess#keepRefToField() */ )
      {
        int weakest = weakest( getAccess( (int)exField.flags_field ), getAccess( flags ) );

        if( exField.enclClass() == classSym )
        {
          // make the existing field accessible according to the weakest of property methods
          int declaredAccess = (int)exField.flags_field & (PUBLIC | PROTECTED | PRIVATE);
          exField.flags_field = exField.flags_field & ~(PUBLIC | PROTECTED | PRIVATE) | weakest;
          addField( exField, null, varClass, declaredAccess );
          return null; // don't create another one
        }
        if( isPropertyField( exField ) )
        {
          return new Pair<>( weakest, exField ); // existing field is compatible, create one with `weakest` access (or weaker)
        }
      }
      return null; // existing field is in conflict, don't create another one
    }
    return new Pair<>( MAX_VALUE, null ); // no existing field, create one
  }

  private Symbol[] findExistingFieldInAncestry( Name name, Symbol.TypeSymbol c, ClassSymbol origin )
  {
    if( !(c instanceof ClassSymbol) )
    {
      return null;
    }

    Types types = Types.instance( context() );
    for( Symbol sym : IDynamicJdk.instance().getMembersByName( (ClassSymbol)c, name ) )
    {
      if( sym instanceof VarSymbol )
      {
        return isInherited( sym, origin ) ? new Symbol[]{sym} : new Symbol[]{};
      }
    }
    Type st = types.supertype( c.type );
    if( st != null && st.hasTag( CLASS ) )
    {
      Symbol[] sym = findExistingFieldInAncestry( name, st.tsym, origin );
      if( sym != null )
      {
        return sym;
      }
    }
    for( List<Type> l = types.interfaces( c.type ); l.nonEmpty(); l = l.tail )
    {
      Symbol[] sym = findExistingFieldInAncestry( name, l.head.tsym, origin );
      if( sym != null && sym.length > 0 && !Modifier.isStatic( (int)sym[0].flags_field ) )
      {
        return sym;
      }
    }
    return null;
  }

  private static class PropAttrs
  {
    String _prefix;
    String _name;
    Type _type;
    Symbol.MethodSymbol _m;

    PropAttrs( String prefix, String name, Type type, Symbol.MethodSymbol m )
    {
      _prefix = prefix;
      _name = name;
      _type = type;
      _m = m;
    }
  }

  private PropAttrs derivePropertyNameFromGetter( Symbol.MethodSymbol m )
  {
    Symtab symtab = Symtab.instance( context() );
    if( m.getReturnType() == symtab.voidType || !m.getParameters().isEmpty() )
    {
      return null;
    }

    PropAttrs derived = deriveName( m, "get", m.getReturnType() );
    return derived == null ? deriveName( m, "is", m.getReturnType() ) : derived;
  }

  private PropAttrs derivePropertyNameFromSetter( Symbol.MethodSymbol m )
  {
    return m.getParameters().length() != 1 ? null : deriveName( m, "set", m.getParameters().get( 0 ).type );
  }

  private PropAttrs deriveName( Symbol.MethodSymbol m, String prefix, Type type )
  {
    String name = m.getSimpleName().toString();
    if( name.startsWith( prefix ) )
    {
      String derived = name.substring( prefix.length() );
      if( !derived.isEmpty() )
      {
        char first = derived.charAt( 0 );
        if( Character.isUpperCase( first ) || first == '$' )
        {
          return new PropAttrs( prefix, ManStringUtil.uncapitalize( derived ), type, m );
        }
        else if( first == '_' )
        {
          StringBuilder sb = new StringBuilder( derived );
          while( sb.length() > 0 && sb.charAt( 0 ) == '_' )
          {
            sb.deleteCharAt( 0 );
          }
          if( sb.length() > 0 )
          {
            return new PropAttrs( prefix, sb.toString(), type, m );
          }
        }
      }
    }
    return null;
  }
}
