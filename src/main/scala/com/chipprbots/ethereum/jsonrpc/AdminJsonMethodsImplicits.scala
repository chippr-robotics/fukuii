package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST
import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JBool
import org.json4s.JsonAST.JNull
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.jsonrpc.AdminService._
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.serialization.JsonEncoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder
import com.chipprbots.ethereum.jsonrpc.serialization.JsonMethodDecoder.NoParamsMethodDecoder

object AdminJsonMethodsImplicits extends JsonMethodsImplicits {

  implicit val admin_nodeInfo: NoParamsMethodDecoder[AdminNodeInfoRequest] with JsonEncoder[AdminNodeInfoResponse] =
    new NoParamsMethodDecoder(AdminNodeInfoRequest()) with JsonEncoder[AdminNodeInfoResponse] {
      override def encodeJson(t: AdminNodeInfoResponse): JValue =
        ("enode" -> t.enode.map(JString(_)).getOrElse(JNull)) ~
          ("id" -> t.id) ~
          ("ip" -> t.ip.map(JString(_)).getOrElse(JNull)) ~
          ("listenAddr" -> t.listenAddr.map(JString(_)).getOrElse(JNull)) ~
          ("name" -> t.name) ~
          ("ports" -> JObject(t.ports.toList.map { case (k, v) => k -> org.json4s.JsonAST.JInt(v) })) ~
          ("protocols" -> JObject(t.protocols.toList.map { case (k, v) => k -> JString(v) }))
    }

  implicit val admin_peers: NoParamsMethodDecoder[AdminPeersRequest] with JsonEncoder[AdminPeersResponse] =
    new NoParamsMethodDecoder(AdminPeersRequest()) with JsonEncoder[AdminPeersResponse] {
      override def encodeJson(t: AdminPeersResponse): JValue =
        JArray(t.peers.toList.map { peer =>
          ("id" -> peer.id) ~
            ("name" -> peer.name) ~
            ("network" -> (("remoteAddress" -> peer.remoteAddress) ~ ("inbound" -> peer.inbound)))
        })
    }

  implicit val admin_addPeer
      : JsonMethodDecoder[AdminAddPeerRequest] with JsonEncoder[AdminAddPeerResponse] =
    new JsonMethodDecoder[AdminAddPeerRequest] with JsonEncoder[AdminAddPeerResponse] {
      override def decodeJson(params: Option[JsonAST.JArray]): Either[JsonRpcError, AdminAddPeerRequest] =
        params match {
          case Some(JArray(JString(enodeUrl) :: Nil)) => Right(AdminAddPeerRequest(enodeUrl))
          case _                                       => Left(InvalidParams())
        }

      override def encodeJson(t: AdminAddPeerResponse): JValue = JBool(t.success)
    }

  implicit val admin_removePeer
      : JsonMethodDecoder[AdminRemovePeerRequest] with JsonEncoder[AdminRemovePeerResponse] =
    new JsonMethodDecoder[AdminRemovePeerRequest] with JsonEncoder[AdminRemovePeerResponse] {
      override def decodeJson(params: Option[JsonAST.JArray]): Either[JsonRpcError, AdminRemovePeerRequest] =
        params match {
          case Some(JArray(JString(enodeUrl) :: Nil)) => Right(AdminRemovePeerRequest(enodeUrl))
          case _                                       => Left(InvalidParams())
        }

      override def encodeJson(t: AdminRemovePeerResponse): JValue = JBool(t.success)
    }

  /** Besu AdminChangeLogLevel: params[0] = level string, params[1] = optional String[] log filters.
    * Encodes as null on success (Besu returns JsonRpcSuccessResponse with no result value).
    */
  implicit val admin_changeLogLevel
      : JsonMethodDecoder[AdminChangeLogLevelRequest] with JsonEncoder[AdminChangeLogLevelResponse] =
    new JsonMethodDecoder[AdminChangeLogLevelRequest] with JsonEncoder[AdminChangeLogLevelResponse] {
      override def decodeJson(
          params: Option[JsonAST.JArray]
      ): Either[JsonRpcError, AdminChangeLogLevelRequest] =
        params match {
          case Some(JArray(JString(level) :: Nil)) =>
            Right(AdminChangeLogLevelRequest(level, None))
          case Some(JArray(JString(level) :: JArray(filters) :: Nil)) =>
            val logFilters = filters.collect { case JString(f) => f }
            Right(AdminChangeLogLevelRequest(level, Some(logFilters)))
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: AdminChangeLogLevelResponse): JValue = JNull
    }

  implicit val admin_datadir: NoParamsMethodDecoder[AdminDatadirRequest] with JsonEncoder[AdminDatadirResponse] =
    new NoParamsMethodDecoder(AdminDatadirRequest()) with JsonEncoder[AdminDatadirResponse] {
      override def encodeJson(t: AdminDatadirResponse): JValue = JString(t.datadir)
    }

  implicit val admin_exportChain
      : JsonMethodDecoder[AdminExportChainRequest] with JsonEncoder[AdminExportChainResponse] =
    new JsonMethodDecoder[AdminExportChainRequest] with JsonEncoder[AdminExportChainResponse] {
      override def decodeJson(params: Option[JsonAST.JArray]): Either[JsonRpcError, AdminExportChainRequest] =
        params match {
          case Some(JArray(JString(file) :: Nil)) =>
            Right(AdminExportChainRequest(file, None, None))
          case Some(JArray(JString(file) :: first :: Nil)) =>
            extractQuantity(first).map(f => AdminExportChainRequest(file, Some(f), None))
          case Some(JArray(JString(file) :: first :: last :: Nil)) =>
            for {
              f <- extractQuantity(first)
              l <- extractQuantity(last)
            } yield AdminExportChainRequest(file, Some(f), Some(l))
          case _ => Left(InvalidParams())
        }

      override def encodeJson(t: AdminExportChainResponse): JValue = JBool(t.success)
    }

  implicit val admin_importChain
      : JsonMethodDecoder[AdminImportChainRequest] with JsonEncoder[AdminImportChainResponse] =
    new JsonMethodDecoder[AdminImportChainRequest] with JsonEncoder[AdminImportChainResponse] {
      override def decodeJson(params: Option[JsonAST.JArray]): Either[JsonRpcError, AdminImportChainRequest] =
        params match {
          case Some(JArray(JString(file) :: Nil)) => Right(AdminImportChainRequest(file))
          case _                                   => Left(InvalidParams())
        }

      override def encodeJson(t: AdminImportChainResponse): JValue = JBool(t.success)
    }

  implicit val admin_blockIP
      : JsonMethodDecoder[AdminBlockIPRequest] with JsonEncoder[AdminBlockIPResponse] =
    new JsonMethodDecoder[AdminBlockIPRequest] with JsonEncoder[AdminBlockIPResponse] {
      override def decodeJson(params: Option[JsonAST.JArray]): Either[JsonRpcError, AdminBlockIPRequest] =
        params match {
          case Some(JArray(JString(ip) :: Nil)) => Right(AdminBlockIPRequest(ip))
          case _                                 => Left(InvalidParams())
        }

      override def encodeJson(t: AdminBlockIPResponse): JValue = JBool(t.success)
    }

  implicit val admin_unblockIP
      : JsonMethodDecoder[AdminUnblockIPRequest] with JsonEncoder[AdminUnblockIPResponse] =
    new JsonMethodDecoder[AdminUnblockIPRequest] with JsonEncoder[AdminUnblockIPResponse] {
      override def decodeJson(params: Option[JsonAST.JArray]): Either[JsonRpcError, AdminUnblockIPRequest] =
        params match {
          case Some(JArray(JString(ip) :: Nil)) => Right(AdminUnblockIPRequest(ip))
          case _                                 => Left(InvalidParams())
        }

      override def encodeJson(t: AdminUnblockIPResponse): JValue = JBool(t.success)
    }

  implicit val admin_listBlockedIPs
      : NoParamsMethodDecoder[AdminListBlockedIPsRequest] with JsonEncoder[AdminListBlockedIPsResponse] =
    new NoParamsMethodDecoder(AdminListBlockedIPsRequest()) with JsonEncoder[AdminListBlockedIPsResponse] {
      override def encodeJson(t: AdminListBlockedIPsResponse): JValue =
        JArray(t.ips.map(JString(_)))
    }
}
