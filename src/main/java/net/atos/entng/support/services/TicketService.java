/*
 * Copyright © Région Nord Pas de Calais-Picardie,  Département 91, Région Aquitaine-Limousin-Poitou-Charentes, 2016.
 *
 * This file is part of OPEN ENT NG. OPEN ENT NG is a versatile ENT Project based on the JVM and ENT Core Project.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * For the sake of explanation, any module that communicate over native
 * Web protocols, such as HTTP, with OPEN ENT NG is outside the scope of this
 * license and could be license under its own terms. This is merely considered
 * normal use of OPEN ENT NG, and does not fall under the heading of "covered work".
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package net.atos.entng.support.services;

import java.util.List;
import java.util.concurrent.ConcurrentMap;

import org.entcore.common.service.CrudService;
import org.entcore.common.user.UserInfos;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import fr.wseduc.webutils.Either;

public interface TicketService extends CrudService {

	public void createTicket(JsonObject ticket, JsonArray attachments, UserInfos user, String locale, Handler<Either<String, JsonObject>> handler);

	public void updateTicket(String id, JsonObject data, UserInfos user,
			Handler<Either<String, JsonObject>> handler);

	public void listTickets(UserInfos user, Handler<Either<String, JsonArray>> handler);

	public void listMyTickets(UserInfos user, Handler<Either<String, JsonArray>> handler);

	public void getTicketForEscalation(String ticketId, Handler<Either<String, JsonObject>> handler);

	public void getTicketIdAndSchoolId(Number issueId, Handler<Either<String, JsonObject>> handler);

	/**
	 * @param attachmentMap : key = attachmentId in bug tracker, value = attachmentId in gridfs
	 */
	public void endSuccessfulEscalation(String ticketId, JsonObject issue, Number issueId,
			ConcurrentMap<Integer, String> attachmentMap, UserInfos user, Handler<Either<String, JsonObject>> handler);

	public void endFailedEscalation(String ticketId, UserInfos user, Handler<Either<String, JsonObject>> handler);

	public void updateIssue(Number issueId, String content, Handler<Either<String, JsonObject>> handler);

    public void updateEventCount(String ticketId, Handler<Either<String, JsonObject>> handler);

    public void createTicketHisto(String ticketId, String event, int status, String userid, Handler<Either<String, JsonObject>> handler);

    public void getTicketFromIssueId(String issueId, Handler<Either<String, JsonObject>> handler);

	public void getLastIssuesUpdate(Handler<Either<String, JsonArray>> handler);

	/**
	 * Given a list of issue ids (parameter "issueIds"), return the issue ids that exist in database and their attachments' ids
	 */
	public void listExistingIssues(Number[] issueIds, Handler<Either<String, JsonArray>> handler);

	public void getIssue(String ticketId, Handler<Either<String, JsonArray>> handler);

	public void getIssueAttachmentName(String gridfsId, Handler<Either<String, JsonObject>> handler);

	public void insertIssueAttachment(Number issueId, JsonObject attachment, Handler<Either<String, JsonArray>> handler);

    public void updateTicketStatus(Integer newStatus, List<Integer> ids, Handler<Either<String, JsonObject>> handler);

    public void listEvents(String ticketId, Handler<Either<String, JsonArray>> handler);

}
