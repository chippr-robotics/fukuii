package com.chipprbots.ethereum.jsonrpc.server.http

import javax.net.ssl.SSLSession

import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.tls.Caller
import com.chipprbots.ethereum.tls.IdentityRules

/** What one caller is served on a listener: its controller, whether it gets GraphQL, and its denial label. */
final case class RpcCaller(controller: JsonRpcBaseController, graphQl: Boolean, label: Option[String])

/** The controller each caller of a listener is served by (spec 030 FR-020–FR-028, research D-3, D-4).
  *
  * On a listener that asks for client certificates (`want`, `need`), every caller is resolved from its TLS session: a
  * matched rule's controller serves the rule's namespaces, and anonymous and unmatched callers are served the
  * transport's own. Each controller is built once, here, so a request costs a session lookup and a map lookup. On any
  * other listener, every request is served by the transport's controller, as before.
  */
trait RpcCallers:
  def forSession(session: Option[SSLSession]): RpcCaller

  /** The rules this listener serves, for its start-up lines; empty where it resolves no identity. */
  def rules: IdentityRules

object RpcCallers:

  /** No identity resolution: the transport's controller for every request, GraphQL as the node serves it. */
  def none(controller: JsonRpcBaseController): RpcCallers = new RpcCallers:
    private val caller = RpcCaller(controller, graphQl = true, label = None)
    override def forSession(session: Option[SSLSession]): RpcCaller = caller
    override def rules: IdentityRules = IdentityRules.Empty

  /** @param transportApis
    *   the listener's own `apis`: what anonymous and unmatched callers are served (FR-021, FR-022)
    * @param controllerFor
    *   a controller serving the given namespaces, its denials counted under the given label
    * @param graphQlEnabled
    *   `network.rpc.graphql.enabled`
    */
  def apply(
      identityRules: IdentityRules,
      transportApis: Seq[String],
      controllerFor: (Seq[String], String) => JsonRpcBaseController,
      graphQlEnabled: Boolean
  ): RpcCallers = new RpcCallers:
    private val anonymous = RpcCaller(
      controllerFor(transportApis, IdentityRules.AnonymousLabel),
      IdentityRules.graphQlAllowed(Caller.Anonymous, graphQlEnabled),
      Some(IdentityRules.AnonymousLabel)
    )
    private val unmatched = RpcCaller(
      controllerFor(transportApis, IdentityRules.UnmatchedLabel),
      IdentityRules.graphQlAllowed(Caller.Unmatched(""), graphQlEnabled),
      Some(IdentityRules.UnmatchedLabel)
    )
    private val matched: Map[String, RpcCaller] = identityRules.rules.map { rule =>
      val caller = Caller.Matched(rule)
      rule.name -> RpcCaller(
        controllerFor(IdentityRules.effectiveApis(caller, transportApis), rule.name),
        IdentityRules.graphQlAllowed(caller, graphQlEnabled),
        Some(rule.name)
      )
    }.toMap

    override def rules: IdentityRules = identityRules

    override def forSession(session: Option[SSLSession]): RpcCaller =
      session.map(identityRules.resolve) match
        case Some(Caller.Matched(rule))    => matched(rule.name)
        case Some(Caller.Unmatched(_))     => unmatched
        case Some(Caller.Anonymous) | None => anonymous
