// Enum based on the following article : https://stijndewitt.wordpress.com/2014/01/26/enums-in-javascript/
model.ticketStatusEnum = {
	NEW: 1,
	OPENED: 2,
	RESOLVED: 3,
	ClOSED: 4,
	properties: {
	    1: {i18n: "support.ticket.status.new", value: 1},
	    2: {i18n: "support.ticket.status.opened", value: 2},
	    3: {i18n: "support.ticket.status.resolved", value: 3},
	    4: {i18n: "support.ticket.status.closed", value: 4}
	}
};

if (Object.freeze) {
	Object.freeze(model.ticketStatusEnum);	
}

// Constants for escalationStatus
model.escalationStatuses = {
	NOT_DONE: 1,
	IN_PROGRESS: 2,
	SUCCESSFUL: 3,
	FAILED: 4
};

if (Object.freeze) {
	Object.freeze(model.escalationStatuses);
}


function Comment(){}

function Attachment(){}

function Ticket(){
	this.collection(Comment);
	this.collection(Attachment);
}

Ticket.prototype.createTicket = function(data, callback) {
	http().postJson('/support/ticket', data).done(function(result){
		this.updateData(result);
		model.tickets.push(this);
		if(typeof callback === 'function'){
			callback();
		}
	}.bind(this));
};

Ticket.prototype.updateTicket = function(data, callback) {
	http().putJson('/support/ticket/' + this.id, data).done(function(result){
		this.updateData(result);
		this.trigger('change');
		if(typeof callback === 'function'){
			callback();
		}
	}.bind(this));
};

Ticket.prototype.escalateTicket = function(callback, errorCallback, badRequestCallback) {
	http().postJson('/support/ticket/' + this.id + '/escalate', null, {requestName: 'escalation-request' })
	.done(function(result){
			this.escalation_status = model.escalationStatuses.SUCCESSFUL;
			this.issue = result.issue;
			this.trigger('change');
			if(typeof callback === 'function'){
				callback();
			}
		}.bind(this)
	)
	.e500(function(){
			this.escalation_status = model.escalationStatuses.FAILED;
			this.trigger('change');
			if(typeof errorCallback === 'function'){
				errorCallback();
			}
		}.bind(this)
	)
	.e400(function(){
		this.escalation_status = model.escalationStatuses.NOT_DONE;
		this.trigger('change');
		if(typeof badRequestCallback === 'function'){
			badRequestCallback();
		}
	}.bind(this));
};

Ticket.prototype.toJSON = function() {
	var json = {
		    subject : this.subject,
		    description : this.description,
		    category : this.category,
		    school_id : this.school_id
	};
	if(this.status !== undefined) {
		json.status = this.status;
	}
	if(this.newComment !== undefined) {
		json.newComment = this.newComment;
	}
	if(this.newAttachments && this.newAttachments.length > 0) {
		json.attachments = [];
		for (var i=0; i < this.newAttachments.length; i++) {
			json.attachments.push({
				id: this.newAttachments[i]._id, 
				name: this.newAttachments[i].title,
				size: this.newAttachments[i].metadata.size
			});
		}
	}
	
	return json;
};

Ticket.prototype.getComments = function(callback) {
	http().get('/support/ticket/' + this.id + '/comments').done(function(result){
		if(result.length > 0) {
			this.comments.load(result);
		}
		if(typeof callback === 'function'){
			callback();
		}
	}.bind(this));
};

Ticket.prototype.getAttachments = function(callback) {
	http().get('/support/ticket/' + this.id + '/attachments').done(function(result){
		if(result.length > 0) {
			this.attachments.load(result);
		}
		if(typeof callback === 'function'){
			callback();
		}
	}.bind(this));
};

Ticket.prototype.getBugTrackerIssue = function(callback) {
	http().get('/support/ticket/' + this.id + '/bugtrackerissue').done(function(result){
		if(result.length > 0 && result[0] && result[0].content) {
			// JSON type in PostgreSQL is sent as a JSON string. Parse it
			var content = JSON.parse(result[0].content);
			if(content && content.issue) {
				this.issue = content.issue;
				
				var attachments = JSON.parse(result[0].attachments);
				if(attachments && attachments.length > 0) {
					// add fields "document_id" and "gridfs_id" to each attachment in variable "content.issue"
					this.issue.attachments = _.map(this.issue.attachments, function(pAttachment) {
						var anAttachment = _.find(attachments, function(att) {
							 return att.id === pAttachment.id;
						});
						pAttachment.document_id = anAttachment.document_id;
						pAttachment.gridfs_id = anAttachment.gridfs_id;
						return pAttachment;
					});
				}
			}
		}
		if(typeof callback === 'function'){
			callback();
		}
	}.bind(this));
};

Ticket.prototype.commentIssue = function(callback, errorCallback) {
	http().postJson('/support/issue/' + this.issue.id + '/comment', {content: this.issue.newComment})
	.done(function(result){
			this.issue = result.issue;
			this.trigger('change');
			if(typeof callback === 'function'){
				callback();
			}
		}.bind(this)
	)
	.e500(function(result){
			if(typeof errorCallback === 'function'){
				errorCallback(result.error);
			}
		}.bind(this)
	);
};

model.build = function() {
	model.me.workflow.load(['support']);
	this.makeModels([ Ticket, Comment, Attachment ]);

	this.collection(Ticket, {
		sync : function() {
			http().get('/support/tickets').done(function(tickets) {
				this.load(tickets);
			}.bind(this));
		}, 
		behaviours: 'support'
	});
};