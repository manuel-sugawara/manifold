/*
 * Copyright (c) 2023 - Manifold Systems LLC
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

package manifold.sql.rt.impl.accessors;

import manifold.sql.rt.api.BaseElement;
import manifold.sql.rt.api.ValueAccessor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public class BitValueAccessor implements ValueAccessor
{
  @Override
  public int getJdbcType()
  {
    return Types.BIT;
  }

  @Override
  public Class<?> getJavaType( BaseElement elem )
  {
    if( elem.getSize() > 1 )
    {
      return byte[].class;
    }
    return elem.canBeNull() ? Boolean.class : boolean.class;
  }

  @Override
  public Object getRowValue( ResultSet rs, BaseElement elem ) throws SQLException
  {
    if( elem.getSize() > 1 )
    {
      return rs.getBytes( elem.getPosition() );
    }
    boolean value = rs.getBoolean( elem.getPosition() );
    return !value && rs.wasNull() ? null : value;
  }

  @Override
  public void setParameter( PreparedStatement ps, int pos, Object value ) throws SQLException
  {
    if( value == null )
    {
      ps.setNull( pos, getJdbcType() );
    }
    else if( value instanceof byte[] )
    {
      ps.setBytes( pos, (byte[])value );
    }
    else if( value instanceof Boolean )
    {
      ps.setBoolean( pos, (boolean)value );
    }
    else
    {
      ps.setObject( pos, value, Types.BOOLEAN );
    }
  }
}
