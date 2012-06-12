/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mail.browse;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.mail.R;
import com.android.mail.providers.Attachment;
import com.android.mail.providers.UIProvider.AttachmentDestination;
import com.android.mail.providers.UIProvider.AttachmentState;
import com.android.mail.utils.AttachmentUtils;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.MimeType;
import com.android.mail.utils.Utils;

/**
 * View for a single attachment in conversation view. Shows download status and allows launching
 * intents to act on an attachment.
 *
 */
public class MessageAttachmentBar extends GridLayout implements OnClickListener,
        OnMenuItemClickListener, AttachmentViewInterface {

    private Attachment mAttachment;
    private TextView mTitle;
    private TextView mSubTitle;
    private String mAttachmentSizeText;
    private String mDisplayType;
    private ProgressBar mProgress;
    private ImageView mCancelButton;
    private PopupMenu mPopup;
    private ImageView mOverflowButton;

    private final AttachmentActionHandler mActionHandler;

    private static final String LOG_TAG = new LogUtils().getLogTag();

    public MessageAttachmentBar(Context context) {
        this(context, null);
    }

    public MessageAttachmentBar(Context context, AttributeSet attrs) {
        super(context, attrs);

        mActionHandler = new AttachmentActionHandler(context, this);
    }

    public static MessageAttachmentBar inflate(LayoutInflater inflater, ViewGroup parent) {
        MessageAttachmentBar view = (MessageAttachmentBar) inflater.inflate(
                R.layout.conversation_message_attachment_bar, parent, false);
        return view;
    }

    /**
     * Render or update an attachment's view. This happens immediately upon instantiation, and
     * repeatedly as status updates stream in, so only properties with new or changed values will
     * cause sub-views to update.
     *
     */
    public void render(Attachment attachment) {
        final Attachment prevAttachment = mAttachment;
        mAttachment = attachment;
        mActionHandler.setAttachment(mAttachment);

        LogUtils.d(LOG_TAG, "got attachment list row: name=%s state/dest=%d/%d dled=%d" +
                " contentUri=%s MIME=%s", attachment.name, attachment.state,
                attachment.destination, attachment.downloadedSize, attachment.contentUri,
                attachment.contentType);

        if (prevAttachment == null || TextUtils.equals(attachment.name, prevAttachment.name)) {
            mTitle.setText(attachment.name);
        }

        if (prevAttachment == null || attachment.size != prevAttachment.size) {
            mAttachmentSizeText = AttachmentUtils.convertToHumanReadableSize(getContext(),
                    attachment.size);
            mDisplayType = AttachmentUtils.getDisplayType(getContext(), attachment);
            updateSubtitleText(null);
        }

        mProgress.setMax(attachment.size);

        updateActions();
        mActionHandler.updateStatus();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mTitle = (TextView) findViewById(R.id.attachment_title);
        mSubTitle = (TextView) findViewById(R.id.attachment_subtitle);
        mProgress = (ProgressBar) findViewById(R.id.attachment_progress);
        mOverflowButton = (ImageView) findViewById(R.id.overflow);
        mCancelButton = (ImageView) findViewById(R.id.cancel_attachment);

        setOnClickListener(this);
        mOverflowButton.setOnClickListener(this);
        mCancelButton.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        onClick(v.getId(), v);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        mPopup.dismiss();
        return onClick(item.getItemId(), null);
    }

    private boolean onClick(int res, View v) {
        switch (res) {
            case R.id.preview_attachment:
                previewAttachment();
                break;
            case R.id.save_attachment:
                if (mAttachment.canSave()) {
                    mActionHandler.startDownloadingAttachment(AttachmentDestination.EXTERNAL);
                }
                break;
            case R.id.cancel_attachment:
                mActionHandler.cancelAttachment();
                break;
            case R.id.overflow: {
                final boolean canSave = mAttachment.canSave() && !mAttachment.isDownloading();
                final boolean canPreview = (mAttachment.previewIntent != null);

                // If no overflow items are visible, just bail out.
                // We shouldn't be able to get here anyhow since the overflow
                // button should be hidden.
                if (!canSave && !canPreview) {
                    break;
                }

                if (mPopup == null) {
                    mPopup = new PopupMenu(getContext(), v);
                    mPopup.getMenuInflater().inflate(R.menu.message_footer_overflow_menu,
                            mPopup.getMenu());
                    mPopup.setOnMenuItemClickListener(this);
                }

                final Menu menu = mPopup.getMenu();
                menu.findItem(R.id.preview_attachment).setVisible(canPreview);
                menu.findItem(R.id.save_attachment).setVisible(canSave);

                mPopup.show();
                break;
            }
            default:
                // Handles clicking the attachment
                // in any area that is not the overflow
                // button or cancel button or one of the
                // overflow items.

                // If we can install, install.
                if (MimeType.isInstallable(mAttachment.contentType)) {
                    mActionHandler.showAttachment(AttachmentDestination.EXTERNAL);
                }
                // If we can view or play with an on-device app,
                // view or play.
                else if (MimeType.isViewable(getContext(), mAttachment.contentType)
                        || MimeType.isPlayable(mAttachment.contentType)) {
                    mActionHandler.showAttachment(AttachmentDestination.CACHE);
                }
                // If we can only preview the attachment, preview.
                else if (mAttachment.previewIntent != null) {
                    previewAttachment();
                }
                // Otherwise, if we cannot do anything, show the info dialog.
                else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                    int dialogMessage = MimeType.isBlocked(mAttachment.contentType)
                            ? R.string.attachment_type_blocked : R.string.no_application_found;
                    builder.setTitle(R.string.more_info_attachment)
                           .setMessage(dialogMessage)
                           .show();
                }
                break;
        }

        return true;
    }

    public void viewAttachment() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        Utils.setIntentDataAndTypeAndNormalize(intent, mAttachment.contentUri,
                mAttachment.contentType);
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // couldn't find activity for View intent
            LogUtils.e(LOG_TAG, "Coun't find Activity for intent", e);
        }
    }

    private void previewAttachment() {
        getContext().startActivity(mAttachment.previewIntent);
    }

    private void setButtonVisible(View button, boolean visible) {
        button.setVisibility(visible ? VISIBLE : GONE);
    }

    /**
     * Update all actions based on current downloading state.
     */
    private void updateActions() {
        // To avoid visibility state transition bugs, every button's visibility should be touched
        // once by this routine.

        final boolean isDownloading = mAttachment.isDownloading();
        final boolean canSave = mAttachment.canSave();
        final boolean canPreview = (mAttachment.previewIntent != null);

        setButtonVisible(mCancelButton, isDownloading);
        setButtonVisible(mOverflowButton, !isDownloading && (canSave || canPreview));
    }

    public void updateStatus() {
        if (mAttachment.state == AttachmentState.FAILED) {
            mSubTitle.setText(getResources().getString(R.string.download_failed));
        } else {
            updateSubtitleText(mAttachment.isSavedToExternal() ?
                    getResources().getString(R.string.saved) : null);
        }
    }

    public void updateProgress(boolean showProgress) {
        if (mAttachment.isDownloading()) {
            mProgress.setProgress(mAttachment.downloadedSize);
            setProgressVisible(true);
            mProgress.setIndeterminate(!showProgress);
        } else {
            setProgressVisible(false);
        }
    }

    private void setProgressVisible(boolean visible) {
        if (visible) {
            mProgress.setVisibility(VISIBLE);
            mSubTitle.setVisibility(INVISIBLE);
        } else {
            mProgress.setVisibility(INVISIBLE);
            mSubTitle.setVisibility(VISIBLE);
        }
    }

    private void updateSubtitleText(String prefix) {
        // TODO: make this a formatted resource when we have a UX design.
        // not worth translation right now.
        StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            sb.append(prefix);
        }
        sb.append(mAttachmentSizeText);
        sb.append(' ');
        sb.append(mDisplayType);
        mSubTitle.setText(sb.toString());
    }
}