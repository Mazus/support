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

package net.atos.entng.support.services.impl;

import static org.entcore.common.sql.Sql.parseId;
import static org.entcore.common.sql.SqlResult.validUniqueResultHandler;
import static org.entcore.common.sql.SqlResult.validResultHandler;

import java.util.List;
import java.util.concurrent.ConcurrentMap;

import fr.wseduc.webutils.http.Renders;
import net.atos.entng.support.enums.BugTracker;
import net.atos.entng.support.enums.EscalationStatus;
import net.atos.entng.support.enums.TicketStatus;
import net.atos.entng.support.services.TicketServiceSql;

import org.entcore.common.service.impl.SqlCrudService;
import org.entcore.common.sql.SqlStatementsBuilder;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserInfos.Function;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import fr.wseduc.webutils.Either;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

public class TicketServiceSqlImpl extends SqlCrudService implements TicketServiceSql {

	private final static String UPSERT_USER_QUERY = "SELECT support.merge_users(?,?)";
    protected static final Logger log = LoggerFactory.getLogger(Renders.class);

	private final BugTracker bugTrackerType;

	public TicketServiceSqlImpl(BugTracker bugTracker) {
		super("support", "tickets");
		bugTrackerType = bugTracker;
	}

	@Override
	public void createTicket(JsonObject ticket, JsonArray attachments, UserInfos user, String locale,
			Handler<Either<String, JsonObject>> handler) {

		SqlStatementsBuilder s = new SqlStatementsBuilder();

		// 1. Upsert user
		String upsertUserQuery = UPSERT_USER_QUERY;
		s.prepared(upsertUserQuery, new JsonArray().add(user.getUserId()).add(user.getUsername()));

		// 2. Create ticket
		ticket.putString("owner", user.getUserId());
        ticket.putString("locale", locale);
		String returnedFields = "id, school_id, status, created, modified, escalation_status, escalation_date";
		s.insert(resourceTable, ticket, returnedFields);

		this.insertAttachments(attachments, user, s, null);

		sql.transaction(s.build(), validUniqueResultHandler(1, handler));
	}

	@Override
	public void updateTicket(String ticketId, JsonObject data, UserInfos user,
			Handler<Either<String, JsonObject>> handler) {

		SqlStatementsBuilder s = new SqlStatementsBuilder();

		// 1. Upsert user
		String upsertUserQuery = UPSERT_USER_QUERY;
		s.prepared(upsertUserQuery, new JsonArray().add(user.getUserId()).add(user.getUsername()));

		// 2. Update ticket
		StringBuilder sb = new StringBuilder();
		JsonArray values = new JsonArray();
		for (String attr : data.getFieldNames()) {
			if( !"newComment".equals(attr) && !"attachments".equals(attr) ) {
				sb.append(attr).append(" = ?, ");
				values.add(data.getValue(attr));
			}
		}
		values.add(parseId(ticketId));

		String updateTicketQuery = "UPDATE support.tickets" +
				" SET " + sb.toString() + "modified = NOW() " +
				"WHERE id = ? RETURNING modified, subject, owner, school_id";
		s.prepared(updateTicketQuery, values);

		// 3. Insert comment
		String comment = data.getString("newComment", null);
		if(comment != null && !comment.trim().isEmpty()) {
			String insertCommentQuery =
					"INSERT INTO support.comments (ticket_id, owner, content) VALUES(?, ?, ?)";
			JsonArray commentValues = new JsonArray();
			commentValues.add(parseId(ticketId))
				.add(user.getUserId())
				.add(comment);
			s.prepared(insertCommentQuery, commentValues);
		}

		// 4. Insert attachments
		JsonArray attachments = data.getArray("attachments", null);
		this.insertAttachments(attachments, user, s, ticketId);

		// Send queries to event bus
		sql.transaction(s.build(), validUniqueResultHandler(1, handler));
	}


	/**
	 * Append query "insert attachments" to SqlStatementsBuilder s
	 *
	 * @param ticketId : must be null when creating a ticket, and supplied when updating a ticket
	 */
	private void insertAttachments(final JsonArray attachments,
			final UserInfos user, final SqlStatementsBuilder s, final String ticketId) {

		if(attachments != null && attachments.size() > 0) {
			StringBuilder query = new StringBuilder();
			query.append("INSERT INTO support.attachments(document_id, name, size, owner, ticket_id)")
				.append(" VALUES");

			JsonArray values = new JsonArray();
			for (Object a : attachments) {
				if(!(a instanceof JsonObject)) continue;
				JsonObject jo = (JsonObject) a;
				query.append("(?, ?, ?, ?, ");
				values.add(jo.getString("id"))
					.add(jo.getString("name"))
					.add(jo.getInteger("size"))
					.add(user.getUserId());

				if(ticketId == null){
					query.append("(SELECT currval('support.tickets_id_seq'))),");
				}
				else {
					query.append("?),");
					values.add(parseId(ticketId));
				}
			}
			// remove trailing comma
			query.deleteCharAt(query.length() - 1);

			s.prepared(query.toString(), values);
		}

	}


	@Override
	public void listTickets(UserInfos user, Handler<Either<String, JsonArray>> handler) {
		StringBuilder query = new StringBuilder();
		query.append("SELECT t.*, u.username AS owner_name,")
			.append("i.content").append(bugTrackerType.getLastIssueUpdateFromPostgresqlJson()).append(" AS last_issue_update, ")
            .append(" substring(t.description, 0, 101)  as short_desc")
			.append(" FROM support.tickets AS t")
			.append(" INNER JOIN support.users AS u ON t.owner = u.id")
			.append(" LEFT JOIN support.bug_tracker_issues AS i ON t.id=i.ticket_id");

		JsonArray values = new JsonArray();
		Function adminLocal = user.getFunctions().get(DefaultFunctions.ADMIN_LOCAL);
		if (adminLocal != null) {
			List<String> scopesList = adminLocal.getScope();
			if(scopesList != null && !scopesList.isEmpty()) {
				query.append(" WHERE t.school_id IN (");
				for (String scope : scopesList) {
					query.append("?,");
					values.addString(scope);
				}
				query.deleteCharAt(query.length() - 1);
				query.append(")");

				// Include tickets created by current user, and linked to a school where he is not local administrator
				query.append(" OR t.owner = ?");
				values.addString(user.getUserId());
			}
		} else {
            query.append(" WHERE t.school_id IN (?)");
            values.addString(user.getStructures().get(0)); // SUPER_ADMIN, has only 1 structure.
        }

		sql.prepared(query.toString(), values, validResultHandler(handler));
	}

	@Override
	public void listMyTickets(UserInfos user, Handler<Either<String, JsonArray>> handler) {
		String query = "SELECT t.*, u.username AS owner_name, substring(t.description, 0, 100)  as short_desc FROM support.tickets AS t"
			+ " INNER JOIN support.users AS u ON t.owner = u.id"
			+ " WHERE t.owner = ?";
		JsonArray values = new JsonArray().add(user.getUserId());

		sql.prepared(query.toString(), values, validResultHandler(handler));
	}

	@Override
	public void getTicketIdAndSchoolId(final Number issueId, final Handler<Either<String, JsonObject>> handler) {
		String query = "SELECT t.id, t.school_id, t.owner, t.locale, t.status FROM support.tickets AS t"
				+ " INNER JOIN support.bug_tracker_issues AS i ON t.id = i.ticket_id"
				+ " WHERE i.id = ?";
		JsonArray values = new JsonArray().addNumber(issueId);

		sql.prepared(query.toString(), values, validUniqueResultHandler(handler));
	};

	/**
	 * If escalation status is "not_done" or "failed", and ticket status is new or opened,
	 * update escalation status to "in_progress" and return the ticket with its attachments' ids and its comments.
	 *
	 * Else (escalation is not allowed) return null.
	 */
	@Override
	public void getTicketForEscalation(String ticketId, Handler<Either<String, JsonObject>> handler) {

		StringBuilder query = new StringBuilder();
		JsonArray values = new JsonArray();

		// 1) WITH query to update status
		query.append("WITH updated_ticket AS (")
			.append(" UPDATE support.tickets")
			.append(" SET escalation_status = ?, escalation_date = NOW()")
			.append(" WHERE id = ?");
		values.add(EscalationStatus.IN_PROGRESS.status())
			.add(parseId(ticketId));

		query.append(" AND escalation_status NOT IN (?, ?)")
			.append(" AND status NOT IN (?, ?)")
			.append(" RETURNING * )");
		values.add(EscalationStatus.IN_PROGRESS.status())
			.add(EscalationStatus.SUCCESSFUL.status())
			.add(TicketStatus.RESOLVED.status())
			.add(TicketStatus.CLOSED.status());

		// 2) query to select ticket, attachments' ids and comments
		query.append("SELECT t.id, t.status, t.subject, t.description, t.category, t.school_id, u.username AS owner_name,")
			/*  When no rows are selected, json_agg returns a JSON array whose objects' fields have null values.
			 * We use CASE to return an empty array instead. */
			.append(" CASE WHEN COUNT(a.document_id) = 0 THEN '[]' ELSE json_agg(DISTINCT a.document_id) END AS attachments,")

			.append(" CASE WHEN COUNT(c.id) = 0 THEN '[]' ")
				.append(" ELSE json_agg(DISTINCT(date_trunc('second', c.created), c.id, c.content, v.username)::support.comment_tuple")
				.append(" ORDER BY (date_trunc('second', c.created), c.id, c.content, v.username)::support.comment_tuple)")
				.append(" END AS comments")

			.append(" FROM updated_ticket AS t")
			.append(" INNER JOIN support.users AS u ON t.owner = u.id")
			.append(" LEFT JOIN support.attachments AS a ON t.id = a.ticket_id")
			.append(" LEFT JOIN support.comments AS c ON t.id = c.ticket_id")
			.append(" LEFT JOIN support.users AS v ON c.owner = v.id")
			.append(" GROUP BY t.id, t.status, t.subject, t.description, t.category, t.school_id, u.username");

		sql.prepared(query.toString(), values, validUniqueResultHandler(handler));
	}


	private void updateTicketAfterEscalation(String ticketId, EscalationStatus targetStatus,
			JsonObject issue, Number issueId, ConcurrentMap<Integer, String> attachmentMap,
			UserInfos user, Handler<Either<String, JsonObject>> handler) {

		// 1. Update escalation status
		String query = "UPDATE support.tickets"
				+ " SET escalation_status = ?, escalation_date = NOW()"
				+ " WHERE id = ?";

		JsonArray values = new JsonArray()
			.add(targetStatus.status())
			.add(parseId(ticketId));

		if(!EscalationStatus.SUCCESSFUL.equals(targetStatus)) {
			sql.prepared(query, values, validUniqueResultHandler(handler));
		}
		else {
			SqlStatementsBuilder statements = new SqlStatementsBuilder();
			statements.prepared(query, values);

			// 2. Upsert user
			String upsertUserQuery = UPSERT_USER_QUERY;
			statements.prepared(upsertUserQuery, new JsonArray().add(user.getUserId()).add(user.getUsername()));

			// 3. Insert bug tracker issue in ENT, so that local administrators can see it
			String insertQuery = "INSERT INTO support.bug_tracker_issues(id, ticket_id, content, owner)"
					+ " VALUES(?, ?, ?::JSON, ?)";

			JsonArray insertValues = new JsonArray().add(issueId)
					.add(parseId(ticketId))
					.addObject(issue)
					.add(user.getUserId());

			statements.prepared(insertQuery, insertValues);

			// 4. Insert attachment (document from workspace) metadata
			if(issue.size() > 0) {
				JsonArray attachments = bugTrackerType.extractAttachmentsFromIssue(issue);
				if(attachments != null && attachments.size() > 0
						&& attachmentMap != null && !attachmentMap.isEmpty()) {
					/*
					 * Example of "attachments" array with one attachment :
					 *
						"attachments":[
							{
							    "id": 784,
							    "filename": "test_pj.png",
							    "filesize": 118639,
							    "content_type": "image/png",
							    "description": "descriptionpj",
							    "content_url": "http: //support.web-education.net/attachments/download/784/test_pj.png"
							}
						]
					 */
					StringBuilder attachmentsQuery = new StringBuilder();
					attachmentsQuery.append("INSERT INTO support.bug_tracker_attachments(id, issue_id, document_id, name, size)")
						.append(" VALUES");

					JsonArray attachmentsValues = new JsonArray();

					for (Object o : attachments) {
						if(!(o instanceof JsonObject)) continue;
						JsonObject attachment = (JsonObject) o;
						attachmentsQuery.append("(?, ?, ?, ?, ?),");

						Number attachmentIdInBugTracker = attachment.getNumber("id");
						attachmentsValues.addNumber(attachmentIdInBugTracker)
							.addNumber(issueId)
							.addString(attachmentMap.get(attachmentIdInBugTracker))
							.addString(attachment.getString("filename"))
							.addNumber(attachment.getNumber("filesize"));
					}
					// remove trailing comma
					attachmentsQuery.deleteCharAt(attachmentsQuery.length() - 1);

					statements.prepared(attachmentsQuery.toString(), attachmentsValues);
				}
			}

			sql.transaction(statements.build(), validUniqueResultHandler(1, handler));
		}

	}

	/**
	 * 	@inheritDoc
	 */
	@Override
	public void endSuccessfulEscalation(String ticketId, JsonObject issue, Number issueId,
			ConcurrentMap<Integer, String> attachmentMap,
			UserInfos user, Handler<Either<String, JsonObject>> handler) {

		this.updateTicketAfterEscalation(ticketId, EscalationStatus.SUCCESSFUL, issue, issueId, attachmentMap, user, handler);
	}

	@Override
	public void endFailedEscalation(String ticketId, UserInfos user, Handler<Either<String, JsonObject>> handler) {
		this.updateTicketAfterEscalation(ticketId, EscalationStatus.FAILED, null, null, null, user, handler);
	}

	@Override
	public void updateIssue(Number issueId, String content, Handler<Either<String, JsonObject>> handler) {
		StringBuilder query = new StringBuilder();
		JsonArray values = new JsonArray();

		// WITH clause to RETURN previous status_id
		query.append("WITH old_issue AS (")
			.append(" SELECT content").append(bugTrackerType.getStatusIdFromPostgresqlJson()).append(" AS status_id")
			.append(" FROM support.bug_tracker_issues")
			.append(" WHERE id = ?)");
		values.addNumber(issueId);

		query.append(" UPDATE support.bug_tracker_issues")
			.append(" SET content = ?::JSON, modified = now()")
			.append(" WHERE id = ?")
			.append(" RETURNING (SELECT status_id FROM old_issue)");

		values.addString(content)
			.addNumber(issueId);

		sql.prepared(query.toString(), values, validUniqueResultHandler(handler));
	}

	@Override
	public void getLastIssuesUpdate(Handler<Either<String, JsonArray>> handler) {
		String query = "SELECT max(content"
				+ bugTrackerType.getLastIssueUpdateFromPostgresqlJson()
				+ ") AS last_update FROM support.bug_tracker_issues";

		sql.raw(query, validResultHandler(handler));
	}

	/**
	 * 	@inheritDoc
	 */
	@Override
	public void listExistingIssues(Number[] issueIds, Handler<Either<String, JsonArray>> handler) {
		/* Return for instance :
			[ { "attachment_ids": "[]", "id": 2836 },
			  { "attachment_ids": "[931, 932, 933, 934, 935, 937, 936]", "id": 2876 } ]
		 */
		StringBuilder query = new StringBuilder("SELECT i.id,")
			.append(" CASE WHEN COUNT(a.id) = 0 THEN '[]'")
			.append(" ELSE json_agg(a.id)")
			.append(" END AS attachment_ids");

		query.append(" FROM support.bug_tracker_issues AS i")
			.append(" LEFT JOIN support.bug_tracker_attachments AS a")
			.append(" ON a.issue_id = i.id");

		JsonArray values = new JsonArray();

		if(issueIds != null && issueIds.length>0) {
			query.append(" WHERE i.id IN (");
			for (Number id : issueIds) {
				query.append("?,");
				values.addNumber(id);
			}
			query.deleteCharAt(query.length() - 1);
			query.append(")");
		}

		query.append(" GROUP BY i.id");

		sql.prepared(query.toString(), values, validResultHandler(handler));
	}

	@Override
	public void getIssue(String ticketId, Handler<Either<String, JsonArray>> handler) {
		/* Field "attachments" will contain for instance :
		 *  [{"id":931,"document_id":null,"gridfs_id":"13237cd7-9567-4810-a85e-39414093e3b5"},
			 {"id":932,"document_id":null,"gridfs_id":"17223f70-d9a8-4983-92b1-d867fc881d44"},
			 {"id":933,"document_id":"c7b27108-8715-40e1-a32f-e90828857c35","gridfs_id":null}]
		 */
		StringBuilder query = new StringBuilder("SELECT i.id, i.content,")
			.append(" CASE WHEN COUNT(a.id) = 0 THEN '[]'")
			.append(" ELSE json_agg((a.id, a.document_id, a.gridfs_id)::support.bug_tracker_attachment_tuple)")
			.append(" END AS attachments")
			.append(" FROM support.bug_tracker_issues AS i")
			.append(" LEFT JOIN support.bug_tracker_attachments AS a ON i.id = a.issue_id")
			.append(" WHERE i.ticket_id = ?")
			.append(" GROUP BY i.id");
		JsonArray values = new JsonArray().add(parseId(ticketId));

		sql.prepared(query.toString(), values, validResultHandler(handler));
	}

	@Override
	public void getIssueAttachmentName(String gridfsId, Handler<Either<String, JsonObject>> handler) {
		String query = "SELECT name FROM support.bug_tracker_attachments WHERE gridfs_id = ?";
		JsonArray values = new JsonArray().add(gridfsId);
		sql.prepared(query, values, validUniqueResultHandler(handler));
	};

	@Override
	public void insertIssueAttachment(Number issueId, JsonObject attachment, Handler<Either<String, JsonArray>> handler) {
		/* NB : Attachments downloaded from bug tracker are saved in gridfs, but not in application "workspace".
		 * => we save a gridfs_id, not a document_id
		 */
		String query = "INSERT INTO support.bug_tracker_attachments(id, issue_id, gridfs_id, name, size) VALUES(?, ?, ?, ?, ?)";

		JsonArray values = new JsonArray();
		JsonObject metadata = attachment.getObject("metadata");

		values.addNumber(attachment.getNumber("id_in_bugtracker"))
			.addNumber(issueId)
			.addString(attachment.getString("_id"))
			.addString(metadata.getString("filename"))
			.addNumber(metadata.getNumber("size"));

		sql.prepared(query.toString(), values, validResultHandler(handler));
	}

    /**
     * Increase the event_count field of ticket table. It means an update has been done.
     * @param ticketId
     * @param handler
     */
    public void updateEventCount(String ticketId, Handler<Either<String, JsonObject>> handler) {
        String query = "UPDATE support.tickets"
                + " SET event_count = event_count + 1 "
                + " WHERE id = ?";

        JsonArray values = new JsonArray()
                .add(parseId(ticketId));

        sql.prepared(query, values, validUniqueResultHandler(handler));
    }

    /**
     * Updating mass modification of tickets status
     * @param newStatus : new status of tickets
     * @param idList : list of the ids that will be modified
     * @param handler
     */
    public void updateTicketStatus(Integer newStatus, List<Integer> idList, Handler<Either<String, JsonObject>> handler) {
        StringBuilder query = new StringBuilder();
        query.append("UPDATE support.tickets");
        query.append(" SET status = ?, event_count = event_count + 1 ");
        query.append(" WHERE id in ( ");

        JsonArray values = new JsonArray();
        values.add(newStatus);

        for (Integer id : idList) {
            query.append("?,");
            values.addNumber(id);
        }
        query.deleteCharAt(query.length() - 1);
        query.append(")");

        sql.prepared(query.toString(), values, validUniqueResultHandler(handler));
    }

    /**
     *
     * @param ticketId : ticket id from which we want to list the history
     * @param handler
     */
    public void listEvents(String ticketId, Handler<Either<String, JsonArray>> handler) {
        String query = "SELECT username, event, status, event_date, user_id, event_type FROM support.tickets_histo th " +
                    " left outer join support.users u on u.id = th.user_id " +
                    " WHERE ticket_id = ? ";
        JsonArray values = new JsonArray().add(parseId(ticketId));
        sql.prepared(query, values, validResultHandler(handler));
    }

    /**
     *
     * @param issueId : bug tracker number from which we want the linked ticket
     * @param handler
     */
    public void getTicketFromIssueId(String issueId, Handler<Either<String, JsonObject>> handler) {
        String query = "SELECT t.* " +
                " from support.tickets t" +
                " inner join support.bug_tracker_issues bti on bti.ticket_id = t.id" +
                " WHERE bti.id = ? ";
        JsonArray values = new JsonArray().add(parseId(issueId));
        sql.prepared(query, values, validUniqueResultHandler(handler));
    }


    /**
     *
     * @param ticketId : id of the ticket historized
     * @param event : description of the event
     * @param status : status after the event
     * @param userid : user that made de creation / modification
     * @param eventType : 1 : new ticket /
     *                    2 : ticket updated /
     *                    3 : new comment /
     *                    4 : ticket escalated to bug-tracker /
     *                    5 : new comment from bug-tracker /
     *                    6 : bug-tracker updated.
     * @param handler
     */
    public void createTicketHisto(String ticketId, String event, int status, String userid, int eventType, Handler<Either<String, JsonObject>> handler) {
        String query = "INSERT INTO support.tickets_histo( ticket_id, event, event_date, status, user_id, event_type) "
                + " values( ?, ?, current_timestamp, ?, ?, ? )";

        JsonArray values = new JsonArray()
                .add(parseId(ticketId))
                .add(event)
                .add(status)
                .add(userid)
                .add(eventType);

        sql.prepared(query, values, validUniqueResultHandler(handler));
    }

}
