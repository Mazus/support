package net.atos.entng.support.filters;

import static org.entcore.common.sql.Sql.parseId;

import java.util.Map;

import org.entcore.common.http.filter.ResourcesProvider;
import org.entcore.common.sql.Sql;
import org.entcore.common.sql.SqlResult;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserInfos.Function;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import fr.wseduc.webutils.http.Binding;

public class OwnerOrLocalAdmin implements ResourcesProvider {

	/**
	 * Authorize if user is a local admin, or the ticket's owner
	 */
	@Override
	public void authorize(final HttpServerRequest request, final Binding binding,
			final UserInfos user, final Handler<Boolean> handler) {

		String ticketId = request.params().get("id");
		if (ticketId == null || ticketId.trim().isEmpty() || !(parseId(ticketId) instanceof Integer)) {
			handler.handle(false);
		}

		// Check if current user is a local admin for the ticket's school_id
		Map<String, UserInfos.Function> functions = user.getFunctions();
		if (functions != null  && functions.containsKey(DefaultFunctions.ADMIN_LOCAL)) {
			Function adminLocal = functions.get(DefaultFunctions.ADMIN_LOCAL);
			if (adminLocal == null || adminLocal.getScope() == null || adminLocal.getScope().isEmpty()) {
				handler.handle(false);
			}
			else {
				request.pause();

				StringBuilder query = new StringBuilder();
				query.append("SELECT count(*) FROM support.tickets AS t ")
					.append("WHERE t.id = ? ");
				JsonArray values = new JsonArray();
				values.add(Sql.parseId(ticketId));

				query.append("AND t.school_id IN (");
				for (String scope : adminLocal.getScope()) {
					query.append("?,");
					values.addString(scope);
				}
				query.deleteCharAt(query.length() - 1);
				query.append(")");

				Sql.getInstance().prepared(query.toString(), values, new Handler<Message<JsonObject>>() {
					@Override
					public void handle(Message<JsonObject> message) {
						request.resume();
						Long count = SqlResult.countResult(message);
						handler.handle(count != null && count > 0);
					}
				});
			}

			return;
		}

		// Check if current user is the ticket's owner
		request.pause();
		String query = "SELECT count(*) FROM support.tickets AS t "
				+ "WHERE t.id = ? AND t.owner = ?";

		JsonArray values = new JsonArray();
		values.add(Sql.parseId(ticketId))
			.add(user.getUserId());

		Sql.getInstance().prepared(query, values, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> message) {
				request.resume();
				Long count = SqlResult.countResult(message);
				handler.handle(count != null && count > 0);
			}
		});



	}
}
