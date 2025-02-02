package scala.meta.internal.metals

import java.net.URI

import scala.meta.pc.CancelToken
import scala.meta.pc.SyntheticDecorationsParams

case class CompilerSyntheticDecorationsParams(
    virtualFileParams: CompilerVirtualFileParams,
    inferredTypes: Boolean,
    typeParameters: Boolean,
    implicitParameters: Boolean,
    implicitConversions: Boolean
) extends SyntheticDecorationsParams {
  override def uri(): URI = virtualFileParams.uri
  override def text(): String = virtualFileParams.text
  override def token(): CancelToken = virtualFileParams.token
}
