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
	CANCELLED
}
