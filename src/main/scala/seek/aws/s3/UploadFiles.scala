package seek.aws.s3

import java.io.File

import cats.data.Kleisli
import cats.data.Kleisli._
import cats.effect.IO
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import org.gradle.api.file.{FileTree, FileTreeElement}
import seek.aws.AwsTask

import scala.collection.mutable

class UploadFiles extends AwsTask {

  setDescription("Uploads multiple files to S3")

  private val bucket = lazyProp[String]("bucket")
  def bucket(v: Any): Unit = bucket.set(v)

  private val files = lazyProp[FileTree]("files")
  def files(v: Any): Unit = files.set(v)

  private val prefix = lazyProp[String]("prefix", "")
  def prefix(v: Any): Unit = prefix.set(v)

  private val failIfPrefixExists = lazyProp[Boolean]("failIfPrefixExists", false)
  def failIfPrefixExists(v: Any): Unit = failIfPrefixExists.set(v)

  private val failIfObjectExists = lazyProp[Boolean]("failIfObjectExists", false)
  def failIfObjectExists(v: Any): Unit = failIfObjectExists.set(v)

  private val cleanPrefixBeforeUpload = lazyProp[Boolean]("cleanPrefixBeforeUpload", false)
  def cleanPrefixBeforeUpload(v: Any): Unit = cleanPrefixBeforeUpload.set(v)

  override def run: IO[Unit] =
    for {
      r  <- region
      b  <- bucket.run
      fs <- files.run
      p  <- prefix.run.map(_.stripSuffix("/"))
      m  <- IO.pure(keyFileMap(fs, p))
      c  <- IO.pure(AmazonS3ClientBuilder.standard().withRegion(r).build())
      _  <- checkFailIfPrefixExists(b, p).run(c)
      _  <- checkFailIfObjectExists(b, m.keys.toList).run(c)
      _  <- checkCleanPrefixBeforeUpload(b, p).run(c)
      _  <- uploadAll(b, m).run(c)
    } yield ()

  private def checkFailIfPrefixExists(bucket: String, prefix: String): Kleisli[IO, AmazonS3, Unit] =
    maybeRun(failIfPrefixExists, exists(bucket, prefix),
      raiseError(s"Prefix '${prefix}' already exists in bucket '${bucket}'"))

  private def checkFailIfObjectExists(bucket: String, keys: List[String]): Kleisli[IO, AmazonS3, Unit] =
    maybeRun(failIfObjectExists, existsAny(bucket, keys),
      raiseError(s"One or more objects already exist in bucket '${bucket}'"))

  private def checkCleanPrefixBeforeUpload(bucket: String, prefix: String): Kleisli[IO, AmazonS3, Unit] =
    Kleisli { c =>
      cleanPrefixBeforeUpload.run.flatMap {
        case false => IO.unit
        case true  =>
          if (prefix.isEmpty)
            raiseError("No prefix specified to clean (and refusing to delete entire bucket)")
          else
            deleteAll(bucket, prefix).run(c)
      }
    }

  private def uploadAll(bucket: String, keyFileMap: Map[String, File]): Kleisli[IO, AmazonS3, Unit] =
    keyFileMap.foldLeft(lift[IO, AmazonS3, Unit](IO.unit)) {
      case (z, (k, f)) => z.flatMap(_ => upload(bucket, k, f))
    }

  private def keyFileMap(files: FileTree, prefix: String) = {
    val buf = new mutable.ArrayBuffer[FileTreeElement]()
    files.visit(d => buf += d)
    val elems = buf.filter(e => e.getFile.isFile && e.getFile.exists).toList
    elems.foldLeft(Map.empty[String, File]) { (z, e) =>
      z + (s"${prefix}${e.getRelativePath}" -> e.getFile)
    }
  }
}