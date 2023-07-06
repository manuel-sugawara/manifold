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

package manifold.sql.rt.jdbc;

import manifold.sql.rt.api.BaseElement;
import manifold.sql.rt.api.ValueAccessor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public class BigIntValueAccessor implements ValueAccessor
{
  @Override
  public int getJdbcType()
  {
    return Types.BIGINT;
  }

  @Override
  public Class<?> getJavaType( BaseElement elem )
  {
    return elem.isNullable() ? Long.class : long.class;
  }

  @Override
  public Long getRowValue( ResultSet rs, BaseElement elem ) throws SQLException
  {
    long value = rs.getLong( elem.getPosition() );
    return value == 0 && rs.wasNull() ? null : value;
  }

  @Override
  public void setParameter( PreparedStatement ps, int pos, Object value ) throws SQLException
  {
    if( value == null )
    {
      ps.setNull( pos, getJdbcType() );
    }
    else if( value instanceof Long )
    {
      ps.setLong( pos, (long)value );
    }
    else
    {
      ps.setObject( pos, value, getJdbcType() );
    }
  }
}
