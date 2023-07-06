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

import java.io.ByteArrayInputStream;
import java.sql.*;

public class BlobValueAccessor implements ValueAccessor
{
  @Override
  public int getJdbcType()
  {
    return Types.BLOB;
  }

  @Override
  public Class<?> getJavaType( BaseElement elem )
  {
    return byte[].class;
  }

  @Override
  public byte[] getRowValue( ResultSet rs, BaseElement elem ) throws SQLException
  {
    Blob blob = rs.getBlob( elem.getPosition() );
    if( blob == null )
    {
      return null;
    }

    try
    {
      return blob.getBytes( 1, (int)blob.length() );
    }
    finally
    {
      blob.free();
    }
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
      byte[] bytes = (byte[])value;
      ByteArrayInputStream stream = new ByteArrayInputStream( bytes );
      ps.setBinaryStream( pos, stream, bytes.length );

    }
    else
    {
      ps.setObject( pos, value, getJdbcType() );
    }
  }
}
