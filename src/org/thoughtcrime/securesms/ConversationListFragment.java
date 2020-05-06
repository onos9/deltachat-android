/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.snackbar.Snackbar;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEventCenter;

import org.thoughtcrime.securesms.ConversationListAdapter.ItemClickListener;
import org.thoughtcrime.securesms.components.recyclerview.DeleteItemAnimator;
import org.thoughtcrime.securesms.components.registration.PulsingFloatingActionButton;
import org.thoughtcrime.securesms.components.reminder.DozeReminder;
import org.thoughtcrime.securesms.components.reminder.OutdatedReminder;
import org.thoughtcrime.securesms.components.reminder.Reminder;
import org.thoughtcrime.securesms.components.reminder.ReminderView;
import org.thoughtcrime.securesms.connect.AccountManager;
import org.thoughtcrime.securesms.connect.ApplicationDcContext;
import org.thoughtcrime.securesms.connect.DcChatlistLoader;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.notifications.MessageNotifierCompat;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.guava.Optional;
import org.thoughtcrime.securesms.util.task.SnackbarAsyncTask;
import org.thoughtcrime.securesms.util.views.ProgressDialog;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.thoughtcrime.securesms.util.RelayUtil.REQUEST_RELAY;
import static org.thoughtcrime.securesms.util.RelayUtil.acquireRelayMessageContent;
import static org.thoughtcrime.securesms.util.RelayUtil.getSharedText;
import static org.thoughtcrime.securesms.util.RelayUtil.getSharedUris;
import static org.thoughtcrime.securesms.util.RelayUtil.isRelayingMessageContent;


public class ConversationListFragment extends Fragment
        implements LoaderManager.LoaderCallbacks<DcChatlist>, ActionMode.Callback, ItemClickListener, DcEventCenter.DcEventDelegate {
  public static final String ARCHIVE = "archive";
  public static final String FORWARDING = "for_forwarding";

  @SuppressWarnings("unused")
  private static final String TAG = ConversationListFragment.class.getSimpleName();

  private ActionMode                  actionMode;
  private RecyclerView                list;
  private ReminderView                reminderView;
  private View                        emptyState;
  private TextView                    emptySearch;
  private PulsingFloatingActionButton fab;
  private Locale                      locale;
  private String                      queryFilter  = "";
  private boolean                     archive;
  private boolean                     forwarding;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    locale = (Locale) getArguments().getSerializable(PassphraseRequiredActionBarActivity.LOCALE_EXTRA);
    archive = getArguments().getBoolean(ARCHIVE, false);
    forwarding = getArguments().getBoolean(FORWARDING, false);

    ApplicationDcContext dcContext = DcHelper.getContext(getActivity());
    dcContext.eventCenter.addObserver(DcContext.DC_EVENT_CHAT_MODIFIED, this);
    dcContext.eventCenter.addObserver(DcContext.DC_EVENT_INCOMING_MSG, this);
    dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSGS_CHANGED, this);
    dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSG_DELIVERED, this);
    dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSG_FAILED, this);
    dcContext.eventCenter.addObserver(DcContext.DC_EVENT_MSG_READ, this);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    DcHelper.getContext(getActivity()).eventCenter.removeObservers(this);
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_list_fragment, container, false);

    reminderView = ViewUtil.findById(view, R.id.reminder);
    list         = ViewUtil.findById(view, R.id.list);
    fab          = ViewUtil.findById(view, R.id.fab);
    emptyState   = ViewUtil.findById(view, R.id.empty_state);
    emptySearch  = ViewUtil.findById(view, R.id.empty_search);

    if (archive) fab.setVisibility(View.GONE);
    else         fab.setVisibility(View.VISIBLE);

    reminderView.setOnDismissListener(this::updateReminders);

    list.setHasFixedSize(true);
    list.setLayoutManager(new LinearLayoutManager(getActivity()));
    list.setItemAnimator(new DeleteItemAnimator());

    return view;
  }

  @Override
  public void onActivityCreated(Bundle bundle) {
    super.onActivityCreated(bundle);

    setHasOptionsMenu(true);
    initializeFabClickListener(false);
    initializeListAdapter();
  }

  @Override
  public void onResume() {
    super.onResume();

    updateReminders();
    list.getAdapter().notifyDataSetChanged();
  }

  @Override
  public void onPause() {
    super.onPause();
    fab.stopPulse();
  }

  public void onNewIntent() {
    initializeFabClickListener(actionMode != null);
  }

  public ConversationListAdapter getListAdapter() {
    return (ConversationListAdapter) list.getAdapter();
  }

  private void initializeFabClickListener(boolean isActionMode) {
    Intent intent = new Intent(getActivity(), NewConversationActivity.class);
    if (isRelayingMessageContent(getActivity())) {
      if (isActionMode) {
        Log.e(TAG, "init fab actionmode");
        fab.setOnClickListener(v -> {
          String message = String.format("Do you want to share?");
          Context context = getContext();
          if (context != null) {
            new AlertDialog.Builder(context)
                    .setMessage(message)
                    .setCancelable(false)
                    .setNegativeButton(android.R.string.cancel, ((dialog, which) -> {
                    }))
                    .setPositiveButton(R.string.menu_send, (dialog, which) -> {
                      Log.e(TAG, "sending uris: " + getSharedUris(getActivity()).size() + " text: "+getSharedText(getActivity()));
                      final Set<Long> selectedChats = getListAdapter().getBatchSelections();
                      for (long chatId : selectedChats) {
                        Log.e(TAG, "...to "+chatId);
                        new ConversationActivity.RelayingTask(getActivity(), (int) chatId).execute();
                      }
                    })
                    .show();
          }
        });
      } else {
        acquireRelayMessageContent(getActivity(), intent);
        Log.e(TAG, "init fab nonactionmode");
        fab.setOnClickListener(v -> getActivity().startActivityForResult(intent, REQUEST_RELAY));
      }
    } else {
      Log.e(TAG, "init fab nonrelaying");
      fab.setOnClickListener(v -> startActivity(intent));
      fab.setOnLongClickListener(v -> {
        if (Prefs.isAccountSwitchingEnabled(getActivity())) {
          AccountManager.getInstance().showSwitchAccountMenu(getActivity());
          return true;
        }
        return false;
      });
    }
  }

  @SuppressLint({"StaticFieldLeak", "NewApi"})
  private void updateReminders() {
    new AsyncTask<Context, Void, Optional<? extends Reminder>>() {
      @Override
      protected Optional<? extends Reminder> doInBackground(Context... params) {
        final Context context = params[0];
        try {
          if (DozeReminder.isEligible(context)) {
            DozeReminder.addDozeReminderDeviceMsg(context);
            return Optional.absent();
          } else if (OutdatedReminder.isEligible(context)) {
            return Optional.of(new OutdatedReminder(context));
          }
        } catch (Exception e) {
          e.printStackTrace();
        }

        return Optional.absent();
      }

      @Override
      protected void onPostExecute(Optional<? extends Reminder> reminder) {
        if (reminder.isPresent() && getActivity() != null && !isRemoving()) {
          reminderView.showReminder(reminder.get());
        } else if (!reminder.isPresent()) {
          reminderView.hide();
        }
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, getActivity());
  }

  private void initializeListAdapter() {
    list.setAdapter(new ConversationListAdapter(getActivity(), GlideApp.with(this), locale, null, this));
    getLoaderManager().restartLoader(0, null, this);
  }

  private void handlePinAllSelected() {
    final DcContext dcContext             = DcHelper.getContext(getActivity());
    final Set<Long> selectedConversations = new HashSet<>(getListAdapter().getBatchSelections());
    boolean doPin = areSomeSelectedChatsUnpinned();
    for (long chatId : selectedConversations) {
      dcContext.setChatVisibility((int)chatId,
              doPin? DcChat.DC_CHAT_VISIBILITY_PINNED : DcChat.DC_CHAT_VISIBILITY_NORMAL);
    }
    if (actionMode != null) {
      actionMode.finish();
      actionMode = null;
    }
  }

  @SuppressLint("StaticFieldLeak")
  private void handleArchiveAllSelected() {
    final DcContext dcContext             = DcHelper.getContext(getActivity());
    final Set<Long> selectedConversations = new HashSet<>(getListAdapter().getBatchSelections());
    final boolean   archive               = this.archive;

    int snackBarTitleId;

    if (archive) snackBarTitleId = R.plurals.chat_unarchived;
    else         snackBarTitleId = R.plurals.chat_archived;

    int count            = selectedConversations.size();
    String snackBarTitle = getResources().getQuantityString(snackBarTitleId, count, count);

    new SnackbarAsyncTask<Void>(getView(), snackBarTitle,
                                getString(R.string.undo),
                                Snackbar.LENGTH_LONG, true)
    {

      @Override
      protected void onPostExecute(Void result) {
        super.onPostExecute(result);

        if (actionMode != null) {
          actionMode.finish();
          actionMode = null;
        }
      }

      @Override
      protected void executeAction(@Nullable Void parameter) {
        for (long chatId : selectedConversations) {
          if (chatId == DcChat.DC_CHAT_ID_DEADDROP) {
            dcContext.marknoticedContact(getListAdapter().getDeaddropContactId());
          }
          else {
            dcContext.setChatVisibility((int)chatId,
                    !archive? DcChat.DC_CHAT_VISIBILITY_ARCHIVED : DcChat.DC_CHAT_VISIBILITY_NORMAL);
          }
        }
      }

      @Override
      protected void reverseAction(@Nullable Void parameter) {
        for (long threadId : selectedConversations) {
          dcContext.setChatVisibility((int)threadId,
                  !archive? DcChat.DC_CHAT_VISIBILITY_NORMAL : DcChat.DC_CHAT_VISIBILITY_ARCHIVED);
        }
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  @SuppressLint("StaticFieldLeak")
  private void handleDeleteAllSelected() {
    final DcContext     dcContext          = DcHelper.getContext(getActivity());
    int                 conversationsCount = getListAdapter().getBatchSelections().size();
    AlertDialog.Builder alert              = new AlertDialog.Builder(getActivity());
    alert.setMessage(getActivity().getResources().getQuantityString(R.plurals.ask_delete_chat,
                                                                    conversationsCount, conversationsCount));
    alert.setCancelable(true);

    alert.setPositiveButton(R.string.delete, (dialog, which) -> {
      final Set<Long> selectedConversations = (getListAdapter())
          .getBatchSelections();

      if (!selectedConversations.isEmpty()) {
        new AsyncTask<Void, Void, Void>() {
          private ProgressDialog dialog;

          @Override
          protected void onPreExecute() {
            dialog = ProgressDialog.show(getActivity(),
                "",
                getActivity().getString(R.string.one_moment),
                true, false);
          }

          @Override
          protected Void doInBackground(Void... params) {
            for (long chatId : selectedConversations) {
              if (chatId == DcChat.DC_CHAT_ID_DEADDROP) {
                dcContext.marknoticedContact(getListAdapter().getDeaddropContactId());
              }
              else {
                MessageNotifierCompat.removeNotifications((int) chatId);
                dcContext.deleteChat((int) chatId);
              }
            }
            return null;
          }

          @Override
          protected void onPostExecute(Void result) {
            dialog.dismiss();
            if (actionMode != null) {
              actionMode.finish();
              actionMode = null;
            }
          }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
      }
    });

    alert.setNegativeButton(android.R.string.cancel, null);
    alert.show();
  }

  private void handleSelectAllThreads() {
    getListAdapter().selectAllThreads();
    actionMode.setTitle(String.valueOf(getListAdapter().getBatchSelections().size()));
  }

  private void handleCreateConversation(int chatId) {
    ((ConversationSelectedListener)getActivity()).onCreateConversation(chatId);
  }

  @Override
  public Loader<DcChatlist> onCreateLoader(int arg0, Bundle arg1) {
    int listflags = 0;
    if (archive) {
      listflags |= DcContext.DC_GCL_ARCHIVED_ONLY;
    } else if (forwarding) {
      listflags |= DcContext.DC_GCL_FOR_FORWARDING;
    } else {
      listflags |= DcContext.DC_GCL_ADD_ALLDONE_HINT;
    }
    return new DcChatlistLoader(getActivity(), listflags, queryFilter.isEmpty()? null : queryFilter, 0);
  }


  @Override
  public void onLoadFinished(Loader<DcChatlist> arg0, DcChatlist chatlist) {
    if (chatlist.getCnt() <= 0 && TextUtils.isEmpty(queryFilter) && !archive) {
      list.setVisibility(View.INVISIBLE);
      emptyState.setVisibility(View.VISIBLE);
      emptySearch.setVisibility(View.INVISIBLE);
      fab.startPulse(3 * 1000);
    } else if (chatlist.getCnt() <= 0 && !TextUtils.isEmpty(queryFilter)) {
      list.setVisibility(View.INVISIBLE);
      emptyState.setVisibility(View.GONE);
      emptySearch.setVisibility(View.VISIBLE);
      emptySearch.setText(getString(R.string.search_no_result_for_x, queryFilter));
    } else {
      list.setVisibility(View.VISIBLE);
      emptyState.setVisibility(View.GONE);
      emptySearch.setVisibility(View.INVISIBLE);
      fab.stopPulse();
    }

    getListAdapter().changeData(chatlist);

  }

  @Override
  public void onLoaderReset(Loader<DcChatlist> arg0) {
    getListAdapter().changeData(null);
  }

  @Override
  public void onItemClick(ConversationListItem item) {
    if (actionMode == null) {
      int chatId = (int)item.getChatId();

      if (chatId == DcChat.DC_CHAT_ID_DEADDROP) {
        DcContext dcContext = DcHelper.getContext(getActivity());
        int msgId = item.getMsgId();
        int contactId = item.getContactId();
        DcContact contact = dcContext.getContact(contactId);
        new AlertDialog.Builder(getActivity())
                .setMessage(getActivity().getString(R.string.ask_start_chat_with, contact.getNameNAddr()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                  int belongingChatId = dcContext.createChatByMsgId(msgId);
                  if (belongingChatId != 0) {
                    handleCreateConversation(belongingChatId);
                  }
                })
                .setNegativeButton(R.string.not_now, (dialog, which) -> {
                  dcContext.marknoticedContact(contactId);
                })
                .setNeutralButton(R.string.menu_block_contact, (dialog, which) -> {
                  dcContext.blockContact(contactId, 1);
                })
                .show();
        return;
      }

      handleCreateConversation(chatId);
    } else {
      ConversationListAdapter adapter = (ConversationListAdapter) list.getAdapter();
      adapter.toggleThreadInBatchSet(item.getChatId());

      if (adapter.getBatchSelections().size() == 0) {
        actionMode.finish();
      } else {
        updateActionModeItems(actionMode.getMenu());
        actionMode.setTitle(String.valueOf(getListAdapter().getBatchSelections().size()));
      }

      adapter.notifyDataSetChanged();
    }
  }

  @Override
  public void onItemLongClick(ConversationListItem item) {
    actionMode = ((AppCompatActivity)getActivity()).startSupportActionMode(ConversationListFragment.this);

    if (actionMode != null) {
      getListAdapter().initializeBatchMode(true);
      getListAdapter().toggleThreadInBatchSet(item.getChatId());
      getListAdapter().notifyDataSetChanged();
      Menu menu = actionMode.getMenu();
      if (menu != null) {
        updateActionModeItems(menu);
      }
    }
  }

  @Override
  public void onSwitchToArchive() {
    ((ConversationSelectedListener)getActivity()).onSwitchToArchive();
  }

  public interface ConversationSelectedListener {
    void onCreateConversation(int chatId);
    void onSwitchToArchive();
  }

  private boolean areSomeSelectedChatsUnpinned() {
    ApplicationDcContext dcContext = DcHelper.getContext(getActivity());
    final Set<Long> selectedChats = getListAdapter().getBatchSelections();
    for (long chatId : selectedChats) {
      DcChat dcChat = dcContext.getChat((int)chatId);
      if (dcChat.getVisibility()!=DcChat.DC_CHAT_VISIBILITY_PINNED) {
        return true;
      }
    }
    return false;
  }

  private void updateActionModeItems(Menu menu) {
    if (!isRelayingMessageContent(getActivity())) {
      MenuItem pinItem = menu.findItem(R.id.menu_pin_selected);
      if (areSomeSelectedChatsUnpinned()) {
        pinItem.setIcon(R.drawable.ic_pin_white);
        pinItem.setTitle(R.string.pin_chat);
      } else {
        pinItem.setIcon(R.drawable.ic_unpin_white);
        pinItem.setTitle(R.string.unpin_chat);
      }
    }
  }

  @Override
  public boolean onCreateActionMode(ActionMode mode, Menu menu) {
    if (isRelayingMessageContent(getActivity())) {
      Context context = getContext();
      if (context != null) {
        fab.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_send_sms_white_24dp));
      }
      initializeFabClickListener(true);
    } else {

      MenuInflater inflater = getActivity().getMenuInflater();

      inflater.inflate(R.menu.conversation_list_batch_pin, menu);

      if (archive) inflater.inflate(R.menu.conversation_list_batch_unarchive, menu);
      else inflater.inflate(R.menu.conversation_list_batch_archive, menu);

      inflater.inflate(R.menu.conversation_list, menu);
    }

    mode.setTitle("1");

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      getActivity().getWindow().setStatusBarColor(getResources().getColor(R.color.action_mode_status_bar));
    }

    return true;
  }

  @Override
  public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
    return false;
  }

  @Override
  public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
    switch (item.getItemId()) {
    case R.id.menu_select_all:       handleSelectAllThreads();   return true;
    case R.id.menu_delete_selected:  handleDeleteAllSelected();  return true;
    case R.id.menu_pin_selected:     handlePinAllSelected();     return true;
    case R.id.menu_archive_selected: handleArchiveAllSelected(); return true;
    }

    return false;
  }

  @Override
  public void onDestroyActionMode(ActionMode mode) {
    getListAdapter().initializeBatchMode(false);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      TypedArray color = getActivity().getTheme().obtainStyledAttributes(new int[] {android.R.attr.statusBarColor});
      getActivity().getWindow().setStatusBarColor(color.getColor(0, Color.BLACK));
      color.recycle();
    }

    Context context = getContext();
    if (context != null) {
      fab.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_add_white_24dp));
    }
    initializeFabClickListener(false);

    actionMode = null;
  }

  @Override
  public void handleEvent(int eventId, Object data1, Object data2) {
    getLoaderManager().restartLoader(0, null, this);
  }
}


