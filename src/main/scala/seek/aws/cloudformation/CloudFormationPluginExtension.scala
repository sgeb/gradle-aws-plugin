package seek.aws
package cloudformation

import cats.effect.IO
import org.gradle.api.Project
import simulacrum.typeclass

import scala.collection.JavaConverters._

class CloudFormationPluginExtension(implicit project: Project) {
  import HasLazyProps._

  private[cloudformation] val stackName = lazyProp[String]("stackName")
  def stackName(v: Any): Unit = stackName.set(v)

  private[cloudformation] var parameters: Map[String, Any] = Map()
  def parameters(v: java.util.Map[String, Any]): Unit = parameters = v.asScala.toMap

  private[cloudformation] def resolvedParameters: IO[Map[String, String]] =
    parameters.foldLeft(IO.pure(Map.empty[String, String])) {
      case (z, (k, v)) =>
        val p = lazyProp[String]("")
        p.set(v)
        for {
          m <- z
          x <- p.run
        } yield m + (k -> x)
    }
}

@typeclass trait HasCloudFormationPluginExtension[A] {
  def cfnExt(a: A): CloudFormationPluginExtension
}
