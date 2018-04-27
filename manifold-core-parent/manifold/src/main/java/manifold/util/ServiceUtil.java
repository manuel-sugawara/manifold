package manifold.util;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

public class ServiceUtil
{
  /**
   * Loads, but does not initialize, all <i>registered</i>type services of type `C` managed by this module container.
   * A registered compiler taask is discoverable in the META-INF/ directory as specified by {@link ServiceLoader}.
   */
  public static <C> void loadRegisteredServices( Set<C> services, Class<C> serviceClass, ClassLoader classLoader )
  {
    // Load from Thread Context Loader
    // (currently the IJ plugin creates loaders for accessing source producers from project classpath)

    ServiceLoader<C> loader = ServiceLoader.load( serviceClass );
    Iterator<C> iterator = loader.iterator();
    if( iterator.hasNext() )
    {
      while( iterator.hasNext() )
      {
        try
        {
          C service = iterator.next();
          services.add( service );
        }
        catch( ServiceConfigurationError e )
        {
          // not in the loader, check thread ctx loader next
        }
      }
    }

    if( Thread.currentThread().getContextClassLoader() != classLoader )
    {
      // Also load from this loader
      loader = ServiceLoader.load( serviceClass, classLoader );
      for( iterator = loader.iterator(); iterator.hasNext(); )
      {
        try
        {
          C service = iterator.next();
          if( isAbsent( services, service ) )
          {
            services.add( service );
          }
        }
        catch( ServiceConfigurationError e )
        {
          // avoid chicken/egg errors from attempting to build a module that self-registers a source producer
          // it's important to allow a source producer module to specify its xxx.ITypeManifold file in its META-INF
          // directory so that users of the source producer don't have to
        }
      }
    }
  }

  /**
   * @return True if {@code sp} is not contained within {@code sps}
   */
  static <C> boolean isAbsent( Set<C> services, C service )
  {
    for( C existingSp: services )
    {
      if( existingSp.getClass().equals( service.getClass() ) )
      {
        return false;
      }
    }
    return true;
  }
}
