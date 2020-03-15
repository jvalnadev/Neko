package eu.kanade.tachiyomi.ui.recent_updates

import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.jakewharton.rxbinding.support.v4.widget.refreshes
import com.jakewharton.rxbinding.support.v7.widget.scrollStateChanges
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateService
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.recently_read.RecentlyReadController
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.view.applyWindowInsetsForRootController
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.snack
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.android.synthetic.main.recent_chapters_controller.*
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Fragment that shows recent chapters.
 * Uses [R.layout.recent_chapters_controller].
 * UI related actions should be called from here.
 */
class RecentChaptersController : NucleusController<RecentChaptersPresenter>(),
        ActionMode.Callback,
        FlexibleAdapter.OnItemClickListener,
        FlexibleAdapter.OnItemLongClickListener,
        FlexibleAdapter.OnUpdateListener,
        ConfirmDeleteChaptersDialog.Listener,
        RootSearchInterface,
        RecentChaptersAdapter.OnCoverClickListener {

    init {
        setHasOptionsMenu(true)
    }
    /**
     * Action mode for multiple selection.
     */
    private var actionMode: ActionMode? = null

    /**
     * Adapter containing the recent chapters.
     */
    var adapter: RecentChaptersAdapter? = null
        private set

    private var query = ""

    override fun getTitle(): String? {
        return resources?.getString(R.string.label_recent_updates)
    }

    override fun createPresenter(): RecentChaptersPresenter {
        return RecentChaptersPresenter()
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        return inflater.inflate(R.layout.recent_chapters_controller, container, false)
    }

    /**
     * Called when view is created
     * @param view created view
     */
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        view.applyWindowInsetsForRootController(activity!!.bottom_nav)
        
        view.context.notificationManager.cancel(Notifications.ID_NEW_CHAPTERS)
        // Init RecyclerView and adapter
        val layoutManager = LinearLayoutManager(view.context)
        recycler.layoutManager = layoutManager
        recycler.addItemDecoration(DividerItemDecoration(view.context, DividerItemDecoration.VERTICAL))
        recycler.setHasFixedSize(true)
        adapter = RecentChaptersAdapter(this@RecentChaptersController)
        recycler.adapter = adapter

        recycler.scrollStateChanges().subscribeUntilDestroy {
            // Disable swipe refresh when view is not at the top
            val firstPos = layoutManager.findFirstCompletelyVisibleItemPosition()
            swipe_refresh.isEnabled = firstPos <= 0
        }

        swipe_refresh.setDistanceToTriggerSync((2 * 64 * view.resources.displayMetrics.density).toInt())
        swipe_refresh.refreshes().subscribeUntilDestroy {
            if (!LibraryUpdateService.isRunning()) {
                LibraryUpdateService.start(view.context)
                view.snack(R.string.updating_library) {
                    anchorView = (this@RecentChaptersController.activity as? MainActivity)
                        ?.bottom_nav
                }
            }
            // It can be a very long operation, so we disable swipe refresh and show a snackbar.
            swipe_refresh.isRefreshing = false
        }

        scrollViewWith(recycler, swipeRefreshLayout = swipe_refresh)
    }

    override fun onDestroyView(view: View) {
        adapter = null
        actionMode = null
        super.onDestroyView(view)
    }

    /**
     * Returns selected chapters
     * @return list of selected chapters
     */
    fun getSelectedChapters(): List<RecentChapterItem> {
        val adapter = adapter ?: return emptyList()
        return adapter.selectedPositions.mapNotNull { adapter.getItem(it) as? RecentChapterItem }
    }

    /**
     * Called when item in list is clicked
     * @param position position of clicked item
     */
    override fun onItemClick(view: View?, position: Int): Boolean {
        val adapter = adapter ?: return false

        // Get item from position
        val item = adapter.getItem(position) as? RecentChapterItem ?: return false
        if (actionMode != null && adapter.mode == SelectableAdapter.Mode.MULTI) {
            toggleSelection(position)
            return true
        } else {
            openChapter(item)
            return false
        }
    }

    /**
     * Called when item in list is long clicked
     * @param position position of clicked item
     */
    override fun onItemLongClick(position: Int) {
        if (actionMode == null)
            actionMode = (activity as AppCompatActivity).startSupportActionMode(this)

        toggleSelection(position)
    }

    /**
     * Called to toggle selection
     * @param position position of selected item
     */
    private fun toggleSelection(position: Int) {
        val adapter = adapter ?: return
        adapter.toggleSelection(position)
        actionMode?.invalidate()
    }

    /**
     * Open chapter in reader
     * @param chapter selected chapter
     */
    private fun openChapter(item: RecentChapterItem) {
        val activity = activity ?: return
        val intent = ReaderActivity.newIntent(activity, item.manga, item.chapter)
        startActivity(intent)
    }

    /**
     * Download selected items
     * @param chapters list of selected [RecentChapter]s
     */
    fun downloadChapters(chapters: List<RecentChapterItem>) {
        destroyActionModeIfNeeded()
        presenter.downloadChapters(chapters)
    }

    /**
     * Populate adapter with chapters
     * @param chapters list of [Any]
     */
    fun onNextRecentChapters(chapters: List<RecentChapterItem>) {
        destroyActionModeIfNeeded()
        adapter?.setItems(chapters)
    }

    override fun onUpdateEmptyView(size: Int) {
        if (size > 0) {
            empty_view?.hide()
        } else {
            empty_view?.show(R.drawable.ic_update_black_128dp, R.string.information_no_recent)
        }
    }

    /**
     * Update download status of chapter
     * @param download [Download] object containing download progress.
     */
    fun onChapterStatusChange(download: Download) {
        getHolder(download)?.notifyStatus(download.status)
    }

    /**
     * Returns holder belonging to chapter
     * @param download [Download] object containing download progress.
     */
    private fun getHolder(download: Download): RecentChapterHolder? {
        return recycler?.findViewHolderForItemId(download.chapter.id!!) as? RecentChapterHolder
    }

    /**
     * Mark chapter as read
     * @param chapters list of chapters
     */
    fun markAsRead(chapters: List<RecentChapterItem>) {
        presenter.markChapterRead(chapters, true)
        if (presenter.preferences.removeAfterMarkedAsRead()) {
            deleteChapters(chapters)
        }
    }

    override fun deleteChapters(chaptersToDelete: List<RecentChapterItem>) {
        destroyActionModeIfNeeded()
        presenter.deleteChapters(chaptersToDelete)
    }

    /**
     * Destory [ActionMode] if it's shown
     */
    private fun destroyActionModeIfNeeded() {
        actionMode?.finish()
    }

    /**
     * Mark chapter as unread
     * @param chapters list of selected [RecentChapter]
     */
    fun markAsUnread(chapters: List<RecentChapterItem>) {
        presenter.markChapterRead(chapters, false)
    }

    /**
     * Start downloading chapter
     * @param chapter selected chapter with manga
     */
    fun downloadChapter(chapter: RecentChapterItem) {
        presenter.downloadChapters(listOf(chapter))
    }

    /**
     * Start deleting chapter
     * @param chapter selected chapter with manga
     */
    fun deleteChapter(chapter: RecentChapterItem) {
        presenter.deleteChapters(listOf(chapter))
    }

    override fun onCoverClick(position: Int) {
        val chapterClicked = adapter?.getItem(position) as? RecentChapterItem ?: return
        openManga(chapterClicked)

    }

    fun openManga(chapter: RecentChapterItem) {
        router.pushController(MangaDetailsController(chapter.manga).withFadeTransaction())
    }

    /**
     * Called when chapters are deleted
     */
    fun onChaptersDeleted() {
        adapter?.notifyDataSetChanged()
    }

    /**
     * Called when error while deleting
     * @param error error message
     */
    fun onChaptersDeletedError(error: Throwable) {
        Timber.e(error)
    }

    /**
     * Called when ActionMode created.
     * @param mode the ActionMode object
     * @param menu menu object of ActionMode
     */
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.chapter_recent_selection, menu)
        adapter?.mode = SelectableAdapter.Mode.MULTI
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val count = adapter?.selectedItemCount ?: 0
        if (count == 0) {
            // Destroy action mode if there are no items selected.
            destroyActionModeIfNeeded()
        } else {
            mode.title = resources?.getString(R.string.label_selected, count)
        }
        return false
    }

    /**
     * Called when ActionMode item clicked
     * @param mode the ActionMode object
     * @param item item from ActionMode.
     */
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_mark_as_read -> markAsRead(getSelectedChapters())
            R.id.action_mark_as_unread -> markAsUnread(getSelectedChapters())
            R.id.action_download -> downloadChapters(getSelectedChapters())
            R.id.action_delete -> ConfirmDeleteChaptersDialog(this, getSelectedChapters())
                    .showDialog(router)
            else -> return false
        }
        return true
    }

    /**
     * Called when ActionMode destroyed
     * @param mode the ActionMode object
     */
    override fun onDestroyActionMode(mode: ActionMode?) {
        adapter?.mode = SelectableAdapter.Mode.IDLE
        adapter?.clearSelection()
        actionMode = null
    }


    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.recent_updates, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = resources?.getString(R.string.action_search)
        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, true)
            searchView.clearFocus()
        }
        setOnQueryTextChangeListener(searchView) {
            if (query != it) {
                query = it ?: return@setOnQueryTextChangeListener false
                adapter?.setFilter(query)
                adapter?.performFilter()
            }
            true
        }

        // Fixes problem with the overflow icon showing up in lieu of search
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                activity?.invalidateOptionsMenu()
                return true
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_recents -> {
                router.setRoot(
                    RecentlyReadController().withFadeTransaction().tag(R.id.nav_recents.toString()))
                Injekt.get<PreferencesHelper>().showRecentUpdates().set(false)
                (activity as? MainActivity)?.updateIcons(R.id.nav_recents)
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
