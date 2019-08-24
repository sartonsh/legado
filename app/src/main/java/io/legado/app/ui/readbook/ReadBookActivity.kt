package io.legado.app.ui.readbook

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.Bus
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.receiver.TimeElectricityReceiver
import io.legado.app.service.ReadAloudService
import io.legado.app.ui.changesource.ChangeSourceDialog
import io.legado.app.ui.chapterlist.ChapterListActivity
import io.legado.app.ui.readbook.config.MoreConfigDialog
import io.legado.app.ui.readbook.config.ReadStyleDialog
import io.legado.app.ui.replacerule.ReplaceRuleActivity
import io.legado.app.ui.sourceedit.SourceEditActivity
import io.legado.app.ui.widget.page.ChapterProvider
import io.legado.app.ui.widget.page.PageView
import io.legado.app.ui.widget.page.TextChapter
import io.legado.app.utils.*
import kotlinx.android.synthetic.main.activity_read_book.*
import kotlinx.android.synthetic.main.view_book_page.*
import kotlinx.android.synthetic.main.view_read_menu.*
import kotlinx.android.synthetic.main.view_title_bar.*
import kotlinx.coroutines.launch
import org.jetbrains.anko.sdk27.listeners.onClick
import org.jetbrains.anko.startActivity
import org.jetbrains.anko.startActivityForResult

class ReadBookActivity : VMBaseActivity<ReadBookViewModel>(R.layout.activity_read_book),
    PageView.CallBack,
    ChangeSourceDialog.CallBack,
    ReadBookViewModel.CallBack {
    override val viewModel: ReadBookViewModel
        get() = getViewModel(ReadBookViewModel::class.java)

    private val requestCodeEditSource = 111
    private var changeSourceDialog: ChangeSourceDialog? = null
    private var timeElectricityReceiver: TimeElectricityReceiver? = null
    private var readAloudStatus = Status.STOP

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setSupportActionBar(toolbar)
        initView()
        page_view.callback = this
        viewModel.callBack = this
        viewModel.bookData.observe(this, Observer { title_bar.title = it.name })
        viewModel.chapterListFinish.observe(this, Observer { bookLoadFinish() })
        viewModel.initData(intent)
        savedInstanceState?.let {
            changeSourceDialog = supportFragmentManager.findFragmentByTag(ChangeSourceDialog.tag) as? ChangeSourceDialog
            changeSourceDialog?.callBack = this
        }
        setScreenBrightness(getPrefInt("brightness", 100))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Help.upSystemUiVisibility(window, !read_menu.isVisible)
    }

    override fun onResume() {
        super.onResume()
        Help.upSystemUiVisibility(window, !read_menu.isVisible)
        timeElectricityReceiver = TimeElectricityReceiver.register(this)
    }

    override fun onPause() {
        super.onPause()
        timeElectricityReceiver?.let {
            unregisterReceiver(it)
            timeElectricityReceiver = null
        }
        Help.upSystemUiVisibility(window, !read_menu.isVisible)
    }

    private fun initView() {
        tv_chapter_name.onClick {
            viewModel.bookSource?.let {
                startActivityForResult<SourceEditActivity>(
                    requestCodeEditSource,
                    Pair("data", it.bookSourceUrl)
                )
            }
        }
        tv_chapter_url.onClick {
            runCatching {
                val url = tv_chapter_url.text.toString()
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)
                startActivity(intent)
            }
        }
        read_menu.setListener(object : ReadMenu.Callback {
            override fun setScreenBrightness(value: Int) {
                this@ReadBookActivity.setScreenBrightness(value)
            }

            override fun autoPage() {

            }

            override fun skipToPage(page: Int) {
                viewModel.durPageIndex = page
                page_view.chapterLoadFinish()
            }

            override fun skipPreChapter() {
                viewModel.durPageIndex = 0
                moveToPrevChapter()
            }

            override fun skipNextChapter() {
                moveToNextChapter()
            }

            override fun openReplaceRule() {
                startActivity<ReplaceRuleActivity>()
            }

            override fun openChapterList() {
                viewModel.bookData.value?.let {
                    startActivity<ChapterListActivity>(Pair("bookUrl", it.bookUrl))
                }
            }

            override fun showReadStyle() {
                ReadStyleDialog().show(supportFragmentManager, "readStyle")
            }

            override fun showMoreSetting() {
                MoreConfigDialog().show(supportFragmentManager, "moreConfig")
            }

            override fun menuShow() {
                Help.upSystemUiVisibility(window, !read_menu.isVisible)
            }

            override fun menuHide() {
                Help.upSystemUiVisibility(window, !read_menu.isVisible)
            }
        })
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.read_book, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source -> {
                if (changeSourceDialog == null) {
                    viewModel.bookData.value?.let {
                        changeSourceDialog = ChangeSourceDialog.newInstance(it.name, it.author)
                        changeSourceDialog?.callBack = this
                    }
                }
                changeSourceDialog?.show(supportFragmentManager, ChangeSourceDialog.tag)
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        val keyCode = event?.keyCode
        val action = event?.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !read_menu.menuBarShow) {
                read_menu.runMenuIn()
                return true
            }
            if (!isDown && !read_menu.menuBarShow) {
                read_menu.menuBarShow = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }



    private fun bookLoadFinish() {
        viewModel.bookData.value?.let {
            viewModel.loadContent(it, viewModel.durChapterIndex)
            viewModel.loadContent(it, viewModel.durChapterIndex + 1)
            viewModel.loadContent(it, viewModel.durChapterIndex - 1)
        }
    }

    override fun loadChapter(index: Int) {
        viewModel.bookData.value?.let {
            viewModel.loadContent(it, index)
        }
    }

    override fun contentLoadFinish(bookChapter: BookChapter, content: String) {
        launch {
            when (bookChapter.index) {
                viewModel.durChapterIndex -> {
                    tv_chapter_name.text = bookChapter.title
                    tv_chapter_name.visible()
                    if (!viewModel.isLocalBook) {
                        tv_chapter_url.text = bookChapter.url
                        tv_chapter_url.visible()
                    }
                    ChapterProvider.getTextChapter(content_text_view, bookChapter, content).let {
                        viewModel.curTextChapter = it
                        page_view.chapterLoadFinish()
                        read_menu.upReadProgress(it.pageSize(), viewModel.durPageIndex)
                    }
                }
                viewModel.durChapterIndex - 1 -> {
                    viewModel.prevTextChapter = ChapterProvider.getTextChapter(content_text_view, bookChapter, content)
                    page_view.chapterLoadFinish(-1)
                }
                viewModel.durChapterIndex + 1 -> {
                    viewModel.nextTextChapter = ChapterProvider.getTextChapter(content_text_view, bookChapter, content)
                    page_view.chapterLoadFinish(1)
                }
            }
        }
    }

    override fun chapterSize(): Int {
        return viewModel.chapterSize
    }

    override fun oldBook(): Book? {
        return viewModel.bookData.value
    }

    override fun changeTo(book: Book) {
        viewModel.changeTo(book)
    }

    override fun durChapterIndex(): Int {
        return viewModel.durChapterIndex
    }

    override fun durChapterPos(pageSize: Int): Int {
        if (viewModel.durPageIndex < pageSize) {
            return viewModel.durPageIndex
        }
        return pageSize - 1
    }

    override fun textChapter(chapterOnDur: Int): TextChapter? {
        return when (chapterOnDur) {
            0 -> viewModel.curTextChapter
            1 -> viewModel.nextTextChapter
            -1 -> viewModel.prevTextChapter
            else -> null
        }
    }

    override fun moveToNextChapter() {
        viewModel.moveToNextChapter()
    }

    override fun moveToPrevChapter() {
        viewModel.moveToPrevChapter()
    }

    private fun onClickReadAloud() {
        if (!ReadAloudService.isRun) {
            readAloudStatus = Status.STOP
            SystemUtils.ignoreBatteryOptimization(this)
        }
        when (readAloudStatus) {
            Status.STOP -> {
                viewModel.bookData.value?.let {
                    ReadAloudService.paly(this, it.name, "", "")
                }
            }
            Status.PLAY -> ReadAloudService.pause(this)
            Status.PAUSE -> ReadAloudService.resume(this)
        }
    }

    private fun setScreenBrightness(value: Int) {
        var brightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (this.getPrefBoolean("brightnessAuto").not()) {
            brightness = value.toFloat()
            if (brightness < 1f) brightness = 1f
            brightness = brightness * 1.0f / 255f
        }
        val params = window.attributes
        params.screenBrightness = brightness
        window.attributes = params
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                requestCodeEditSource -> viewModel.upBookSource()
            }
        }
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<Int>(Bus.ALOUD_STATE) { readAloudStatus = it }
        observeEvent<Int>(Bus.TTS_START) {}
        observeEvent<Int>(Bus.TTS_RANGE_START) {}
        observeEvent<String>(Bus.TIME_CHANGED) { page_view.upTime() }
        observeEvent<Int>(Bus.BATTERY_CHANGED) { page_view.upBattery(it) }
        observeEvent<BookChapter>(Bus.OPEN_CHAPTER) { viewModel.openChapter(it) }
        observeEventSticky<Boolean>(Bus.READ_ALOUD) { onClickReadAloud() }
        observeEvent<Boolean>(Bus.UP_CONFIG) {
            Help.upSystemUiVisibility(window, !read_menu.isVisible)
            page_view.upBg()
            content_view.upStyle()
            page_view.upStyle()
        }
    }

}