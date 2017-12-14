package seek.aws
package cloudformation

import cats.effect.IO
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder
import com.amazonaws.services.cloudformation.model.DeleteStackRequest
import seek.aws.cloudformation.instances._
import seek.aws.cloudformation.syntax._

class DeleteStack extends AwsTask {
  import CloudFormation._

  setDescription("Deletes a CloudFormation stack")

  override def run: IO[Unit] =
    for {
      r  <- region
      c  <- IO.pure(AmazonCloudFormationClientBuilder.standard().withRegion(r).build())
      sn <- project.cfnExt.stackName.run
      to <- project.cfnExt.stackWaitTimeout
      _  <- IO(c.deleteStack(new DeleteStackRequest().withStackName(sn)))
      _  <- waitForStack(sn, to).run(c)
    } yield ()
}
