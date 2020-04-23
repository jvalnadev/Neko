package eu.kanade.tachiyomi.ui.main

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebView
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.GestureDetectorCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.FadeChangeHandler
import com.google.android.material.snackbar.Snackbar
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.Migrations
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadService
import eu.kanade.tachiyomi.data.download.DownloadServiceListener
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.base.controller.BaseController
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.recent_updates.RecentChaptersController
import eu.kanade.tachiyomi.ui.recently_read.RecentlyReadController
import eu.kanade.tachiyomi.ui.recents.RecentsController
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.setting.SettingsController
import eu.kanade.tachiyomi.ui.setting.SettingsMainController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsets
import eu.kanade.tachiyomi.util.view.updateLayoutParams
import eu.kanade.tachiyomi.util.view.updatePadding
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.preference.MangadexLoginDialog
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.coroutines.delay
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.abs

open class MainActivity : BaseActivity(), DownloadServiceListener, MangadexLoginDialog.Listener {

    protected lateinit var router: Router

    val source: Source by lazy { Injekt.get<SourceManager>().getMangadex() }

    var drawerArrow: DrawerArrowDrawable? = null
        private set
    private var searchDrawable: Drawable? = null
    private var dismissDrawable: Drawable? = null
    private lateinit var gestureDetector: GestureDetectorCompat

    private var snackBar: Snackbar? = null
    private var extraViewForUndo: View? = null
    private var canDismissSnackBar = false

    private var animationSet: AnimatorSet? = null

    fun setUndoSnackBar(snackBar: Snackbar?, extraViewToCheck: View? = null) {
        this.snackBar = snackBar
        canDismissSnackBar = false
        launchUI {
            delay(1000)
            if (this@MainActivity.snackBar == snackBar) {
                canDismissSnackBar = true
            }
        }
        extraViewForUndo = extraViewToCheck
    }

    lateinit var tabAnimator: TabsAnimator

    override fun onCreate(savedInstanceState: Bundle?) {
        // Create a webview before extensions do or else they will break night mode theme
        // https://stackoverflow.com/questions/54191883
        Timber.d("Manually instantiating WebView to avoid night mode issue.")
        try {
            WebView(applicationContext)
        } catch (e: Exception) {
            Timber.e(e, "Exception when creating webview at start")
        }
        super.onCreate(savedInstanceState)

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }
        gestureDetector = GestureDetectorCompat(this, GestureListener())

        setContentView(R.layout.main_activity)

        setSupportActionBar(toolbar)

        drawerArrow = DrawerArrowDrawable(this)
        drawerArrow?.color = getResourceColor(R.attr.actionBarTintColor)
        searchDrawable = ContextCompat.getDrawable(
            this, R.drawable.ic_search_white_24dp
        )
        dismissDrawable = ContextCompat.getDrawable(
            this, R.drawable.ic_close_white_24dp
        )

        var continueSwitchingTabs = false
        bottom_nav.setOnNavigationItemSelectedListener { item ->
            val id = item.itemId
            val currentController = router.backstack.lastOrNull()?.controller()
            if (!continueSwitchingTabs && currentController is BottomNavBarInterface) {
                if (!currentController.canChangeTabs {
                        continueSwitchingTabs = true
                        this@MainActivity.bottom_nav.selectedItemId = id
                    }) return@setOnNavigationItemSelectedListener false
            }
            continueSwitchingTabs = false
            val currentRoot = router.backstack.firstOrNull()
            if (currentRoot?.tag()?.toIntOrNull() != id) {
                when (id) {
                    R.id.nav_library -> setRoot(LibraryController(), id)
                    R.id.nav_recents -> setRoot(RecentsController(), id)
                    else -> {
                        if (!source.isLogged()) {
                            val dialog = MangadexLoginDialog(source, this)
                            dialog.showDialog(router)
                        } else {
                            setBrowseRoot()
                        }
                    }
                }
            } else if (currentRoot.tag()?.toIntOrNull() == id) {
                if (router.backstackSize == 1) {
                    val controller =
                        router.getControllerWithTag(id.toString()) as? BottomSheetController
                    controller?.toggleSheet()
                }
            }
            true
        }
        val container: ViewGroup = findViewById(R.id.controller_container)

        val content: ViewGroup = findViewById(R.id.main_content)
        DownloadService.addListener(this)
        content.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        container.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        supportActionBar?.setDisplayShowCustomEnabled(true)

        setNavBarColor(content.rootWindowInsets)
        content.doOnApplyWindowInsets { v, insets, _ ->
            setNavBarColor(insets)
            val contextView = window?.decorView?.findViewById<View>(R.id.action_mode_bar)
            contextView?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.systemWindowInsetLeft
                rightMargin = insets.systemWindowInsetRight
            }
            // Consume any horizontal insets and pad all content in. There's not much we can do
            // with horizontal insets
            v.updatePadding(
                left = insets.systemWindowInsetLeft, right = insets.systemWindowInsetRight
            )
            appbar.updatePadding(
                top = insets.systemWindowInsetTop
            )
            bottom_nav.updatePadding(bottom = insets.systemWindowInsetBottom)
        }

        router = Conductor.attachRouter(this, container, savedInstanceState)
        if (!router.hasRootController()) {
            // Set start screen
            if (!handleIntentAction(intent)) {
                bottom_nav.selectedItemId = R.id.nav_library
            }
        }

        toolbar.setNavigationOnClickListener {
            val rootSearchController = router.backstack.lastOrNull()?.controller()
            if (rootSearchController is RootSearchInterface) {
                rootSearchController.expandSearch()
            } else onBackPressed()
        }

        bottom_nav.visibility = if (router.backstackSize > 1) View.GONE else View.VISIBLE
        bottom_nav.alpha = if (router.backstackSize > 1) 0f else 1f
        router.addChangeListener(object : ControllerChangeHandler.ControllerChangeListener {
            override fun onChangeStarted(
                to: Controller?,
                from: Controller?,
                isPush: Boolean,
                container: ViewGroup,
                handler: ControllerChangeHandler
            ) {

                syncActivityViewWithController(to, from, isPush)
                appbar.y = 0f
            }

            override fun onChangeCompleted(
                to: Controller?,
                from: Controller?,
                isPush: Boolean,
                container: ViewGroup,
                handler: ControllerChangeHandler
            ) {
                appbar.y = 0f
            }
        })

        syncActivityViewWithController(router.backstack.lastOrNull()?.controller())

        toolbar.navigationIcon = if (router.backstackSize > 1) drawerArrow else searchDrawable
        (router.backstack.lastOrNull()?.controller() as? BaseController)?.setTitle()
        (router.backstack.lastOrNull()?.controller() as? SettingsController)?.setTitle()

        if (savedInstanceState == null) {
            // Show changelog if needed
            if (Migrations.upgrade(preferences)) {
                if (BuildConfig.DEBUG) {
                    MaterialDialog(this).title(text = "Welcome to the Neko MD2 Beta").message(
                        text = "This beta is for testing the upcoming release. Requests for new additions for this beta will ignored (however suggestions on how to better implement a feature in this beta are welcome).\n\nFor any bugs you come across, there is a bug report button in settings.\n\nAs a reminder this is a *BETA* build; bugs may happen, features may be missing/not implemented yet, and screens can change.\n\nEnjoy and thanks for testing!"
                    ).positiveButton(android.R.string.ok).cancelOnTouchOutside(false).show()
                } else ChangelogDialogController().showDialog(router)
            }
        }
    }

    fun setDismissIcon(enabled: Boolean) {
        toolbar.navigationIcon = if (enabled) dismissDrawable else searchDrawable
    }

    fun showNavigationArrow() {
        toolbar.navigationIcon = drawerArrow
    }

    fun hideNavigationIcon() {
        toolbar.navigationIcon = null
    }

    private fun setNavBarColor(insets: WindowInsets?) {
        if (insets == null) return
        window.navigationBarColor = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            // basically if in landscape on a phone
            // For lollipop, draw opaque nav bar
            if (insets.systemWindowInsetLeft > 0 || insets.systemWindowInsetRight > 0)
                Color.BLACK
            else Color.argb(179, 0, 0, 0)
        }
        // if the android q+ device has gesture nav, transparent nav bar
        // this is here in case some crazy with a notch uses landscape
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (insets
                .systemWindowInsetBottom != insets.tappableElementInsets.bottom)
        ) {
            getColor(android.R.color.transparent)
        }
        // if in landscape with 2/3 button mode, fully opaque nav bar
        else if (insets.systemWindowInsetLeft > 0 || insets.systemWindowInsetRight > 0) {
            getResourceColor(R.attr.colorPrimaryVariant)
        }
        // if in portrait with 2/3 button mode, translucent nav bar
        else {
            ColorUtils.setAlphaComponent(
                getResourceColor(R.attr.colorPrimaryVariant), 179
            )
        }
    }

    override fun startSupportActionMode(callback: androidx.appcompat.view.ActionMode.Callback): androidx.appcompat.view.ActionMode? {
        window?.statusBarColor = getResourceColor(R.attr.colorPrimaryVariant)
        return super.startSupportActionMode(callback)
    }

    override fun onSupportActionModeFinished(mode: androidx.appcompat.view.ActionMode) {
        launchUI {
            val scale = Settings.Global.getFloat(
                contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
            )
            val duration = resources.getInteger(android.R.integer.config_mediumAnimTime) * scale
            delay(duration.toLong())
            delay(100)
            if (Color.alpha(window?.statusBarColor ?: Color.BLACK) >= 255) window?.statusBarColor =
                getResourceColor(
                    android.R.attr.statusBarColor
                )
        }
        super.onSupportActionModeFinished(mode)
    }

    override fun onResume() {
        super.onResume()
        // setting in case someone comes from the search activity to main
        DownloadService.callListeners()
    }

    override fun onPause() {
        super.onPause()
        snackBar?.dismiss()
    }

    /**
     * Called when login dialog is closed, refreshes the adapter.
     *
     * @param source clicked item containing source information.
     */
    override fun siteLoginDialogClosed(source: Source) {
        if (source.isLogged()) {
            setBrowseRoot()
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (!handleIntentAction(intent)) {
            super.onNewIntent(intent)
        }
    }

    fun setBrowseRoot() {
        toolbar.navigationIcon = null
        setRoot(BrowseSourceController(source), R.id.nav_browse)
    }

    protected open fun handleIntentAction(intent: Intent): Boolean {
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) NotificationReceiver.dismissNotification(
            applicationContext, notificationId, intent.getIntExtra("groupId", 0)
        )
        when (intent.action) {
            SHORTCUT_LIBRARY -> bottom_nav.selectedItemId = R.id.nav_library
            SHORTCUT_RECENTLY_UPDATED, SHORTCUT_RECENTLY_READ -> {
                bottom_nav.selectedItemId = R.id.nav_recents
                val controller: Controller = when (intent.action) {
                    SHORTCUT_RECENTLY_UPDATED -> RecentChaptersController()
                    else -> RecentlyReadController()
                }
                router.pushController(controller.withFadeTransaction())
            }
            SHORTCUT_BROWSE -> bottom_nav.selectedItemId = R.id.nav_browse
            SHORTCUT_MANGA -> {
                val extras = intent.extras ?: return false
                if (router.backstack.isEmpty()) bottom_nav.selectedItemId = R.id.nav_library
                router.pushController(MangaDetailsController(extras).withFadeTransaction())
            }
            SHORTCUT_DOWNLOADS -> {
                bottom_nav.selectedItemId = R.id.nav_recents
                router.popToRoot()
                bottom_nav.post {
                    val controller =
                        router.backstack.firstOrNull()?.controller() as? RecentsController
                    controller?.showSheet()
                }
            }

            INTENT_SEARCH -> {
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                if (query != null && query.isNotEmpty()) {
                    if (router.backstackSize > 1) {
                        router.popToRoot()
                    }
                    router.pushController(
                        BrowseSourceController(
                            source,
                            query
                        ).withFadeTransaction()
                    )
                }
            }
            else -> return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadService.removeListener(this)
        toolbar?.setNavigationOnClickListener(null)
    }

    override fun onBackPressed() {
        val sheetController = router.backstack.last().controller() as? BottomSheetController
        if (if (router.backstackSize == 1) !(sheetController?.handleSheetBack() ?: false)
            else !router.handleBack()
        ) {
            SecureActivityDelegate.locked = true
            super.onBackPressed()
        }
    }

    private fun setRoot(controller: Controller, id: Int) {
        router.setRoot(controller.withFadeTransaction().tag(id.toString()))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Initialize option to open catalogue settings.
            R.id.action_settings -> {
                router.pushController(
                    (RouterTransaction.with(SettingsMainController())).popChangeHandler(
                        FadeChangeHandler()
                    ).pushChangeHandler(FadeChangeHandler())
                )
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return super.onOptionsItemSelected(item)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        gestureDetector.onTouchEvent(ev)
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            if (snackBar != null && snackBar!!.isShown) {
                val sRect = Rect()
                snackBar!!.view.getGlobalVisibleRect(sRect)

                val extRect: Rect? = if (extraViewForUndo != null) Rect() else null
                extraViewForUndo?.getGlobalVisibleRect(extRect)
                // This way the snackbar will only be dismissed if
                // the user clicks outside it.
                if (canDismissSnackBar && !sRect.contains(
                        ev.x.toInt(),
                        ev.y.toInt()
                    ) && (extRect == null || !extRect.contains(ev.x.toInt(), ev.y.toInt()))
                ) {
                    snackBar?.dismiss()
                    snackBar = null
                    extraViewForUndo = null
                }
            } else if (snackBar != null) {
                snackBar = null
                extraViewForUndo = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    protected open fun syncActivityViewWithController(
        to: Controller?,
        from: Controller? = null,
        isPush: Boolean = false
    ) {
        if (from is DialogController || to is DialogController) {
            return
        }
        val onRoot = router.backstackSize == 1
        if (onRoot) {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            toolbar.navigationIcon = searchDrawable
        } else {
            showNavigationArrow()
        }
        drawerArrow?.progress = 1f

        if (to !is DialogController) {
            bottom_nav.visibility =
                if (router.backstackSize == 0 || (router.backstackSize <= 1 && !isPush)) View.VISIBLE else bottom_nav.visibility
            animationSet?.cancel()
            animationSet = AnimatorSet()
            val alphaAnimation = ValueAnimator.ofFloat(
                bottom_nav.alpha, if (router.backstackSize > 1) 0f else 1f
            )
            alphaAnimation.addUpdateListener { valueAnimator ->
                bottom_nav.alpha = valueAnimator.animatedValue as Float
            }
            alphaAnimation.addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator?) {
                    bottom_nav.visibility =
                        if (router.backstackSize > 1) View.GONE else View.VISIBLE
                }

                override fun onAnimationCancel(animation: Animator?) {}

                override fun onAnimationRepeat(animation: Animator?) {}

                override fun onAnimationStart(animation: Animator?) {}
            })
            alphaAnimation.duration = 200
            alphaAnimation.startDelay = 50
            animationSet?.playTogether(alphaAnimation)
            animationSet?.start()
        }
    }

    override fun downloadStatusChanged(downloading: Boolean) {
        val downloadManager = Injekt.get<DownloadManager>()
        val hasQueue = downloading || downloadManager.hasQueue()
        launchUI {
            if (hasQueue) {
                bottom_nav?.getOrCreateBadge(R.id.nav_recents)
            } else {
                bottom_nav?.removeBadge(R.id.nav_recents)
            }
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onFling(
            e1: MotionEvent,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            var result = false
            val diffY = e2.y - e1.y
            val diffX = e2.x - e1.x
            if (abs(diffX) <= abs(diffY)) {
                val sheetRect = Rect()
                bottom_nav.getGlobalVisibleRect(sheetRect)
                if (sheetRect.contains(
                        e1.x.toInt(), e1.y.toInt()
                    ) && abs(diffY) > Companion.SWIPE_THRESHOLD && abs(velocityY) > Companion.SWIPE_VELOCITY_THRESHOLD && diffY <= 0
                ) {
                    val bottomSheetController =
                        router.backstack.lastOrNull()?.controller() as? BottomSheetController
                    bottomSheetController?.showSheet()
                }
                result = true
            }
            return result
        }
    }

    companion object {

        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100

        // Shortcut actions
        const val SHORTCUT_LIBRARY = "eu.kanade.tachiyomi.SHOW_LIBRARY"
        const val SHORTCUT_RECENTLY_UPDATED = "eu.kanade.tachiyomi.SHOW_RECENTLY_UPDATED"
        const val SHORTCUT_RECENTLY_READ = "eu.kanade.tachiyomi.SHOW_RECENTLY_READ"
        const val SHORTCUT_BROWSE = "eu.kanade.tachiyomi.SHOW_BROWSE"
        const val SHORTCUT_DOWNLOADS = "eu.kanade.tachiyomi.SHOW_DOWNLOADS"
        const val SHORTCUT_MANGA = "eu.kanade.tachiyomi.SHOW_MANGA"

        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"
    }
}

interface BottomNavBarInterface {
    fun canChangeTabs(block: () -> Unit): Boolean
}

interface RootSearchInterface {
    fun expandSearch() {
        if (this is Controller) activity?.toolbar?.menu?.findItem(R.id.action_search)
            ?.expandActionView()
    }
}

interface BottomSheetController {
    fun showSheet()
    fun toggleSheet()
    fun handleSheetBack(): Boolean
}
