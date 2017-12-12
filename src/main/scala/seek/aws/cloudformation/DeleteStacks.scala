package seek.aws
package cloudformation

import cats.data.Kleisli
import cats.data.Kleisli._
import cats.effect.IO
import com.amazonaws.services.cloudformation.model.{DeleteStackRequest, Stack}
import com.amazonaws.services.cloudformation.{AmazonCloudFormation, AmazonCloudFormationClientBuilder}
import fs2.Stream
import seek.aws.cloudformation.instances._
import seek.aws.cloudformation.syntax._

class DeleteStacks extends AwsTask {

  setDescription("Deletes CloudFormation stacks that match a specified regex")

  private val nameMatching = lazyProperty[String]("nameMatching")
  def nameMatching(v: Any): Unit = nameMatching.set(v)

  private val safetyOn = lazyProperty[Boolean]("safetyOn", true)
  def safetyOn(v: Any): Unit = safetyOn.set(v)

  private val safetyLimit = lazyProperty[Int]("safetyLimit", 3)
  def safetyLimit(v: Any): Unit = safetyLimit.set(v)

  override def run: IO[Unit] =
    for {
      r  <- region
      c  <- IO.pure(AmazonCloudFormationClientBuilder.standard().withRegion(r).build())
      n  <- nameMatching.run
      so <- safetyOn.run
      sl <- safetyLimit.run
      ds <- deleteStacks(n, so, sl).run(c)
      _  <- waitForStacks(ds).run(c)
    } yield ()

  private def deleteStacks(nameMatching: String, safetyOn: Boolean, safetyLimit: Int): Kleisli[IO, AmazonCloudFormation, List[Stack]] =
     Kleisli { c =>
      describeStacks.run(c).filter(_.getStackName.matches(nameMatching)).runLog.flatMap { ss =>
        if (safetyOn && ss.size > safetyLimit)
          raiseError(s"Safety is on and the number of matching stacks (${ss.size}) exceeds the safety limit of ${safetyLimit}")
        else
          ss.foldLeft(IO.unit)((z, s) => z.flatMap(_ => deleteStack(s.getStackName).run(c))).map(_ => ss.toList)
      }
    }

  private def deleteStack(name: String): Kleisli[IO, AmazonCloudFormation, Unit] =
    Kleisli(c => IO(c.deleteStack(new DeleteStackRequest().withStackName(name))))

  private def waitForStacks(stacks: List[Stack]): Kleisli[IO, AmazonCloudFormation, Unit] =
    stacks match {
      case Nil    => lift(IO.unit)
      case h :: t => waitForStack(h.getStackName).flatMap(_ => waitForStacks(t))
    }
}
