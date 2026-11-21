package com.digithink.zsretail.core.communication.enumeration;

/**
 * 3.3.0, communication step 7: where a campaign stands. No draft: a campaign is written when it is launched (the window
 * counts and previews before, without saving anything). See docs/modules/communication.md, "Step 7".
 */
public enum CommunicationCampaignStatus {

	/** Launched: its messages are being made (in the background, by batches) and sent. */
	RUNNING,

	/** Every message made, none waiting any more (sent, failed, expired or skipped). */
	DONE,

	/** Cancelled while running: its waiting messages SKIPPED, the ones sent stay. */
	CANCELLED,
	/**
	 * Step 8c: stopped by the provider's refusal of its template (WhatsApp: not approved, paused, not in that language),
	 * the same for every one of its messages: its waiting messages SKIPPED, the reason kept (stop_reason).
	 */
	STOPPED
}
