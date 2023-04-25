import QtQuick
import QtQuick.Window
import QtQuick.Controls
import QtQuick.Layouts

import PageType 1.0

import "Config"

Window  {
    id: root
    visible: true
    width: GC.screenWidth
    height: GC.screenHeight
    minimumWidth: GC.isDesktop() ? 360 : 0
    minimumHeight: GC.isDesktop() ? 640 : 0
    onClosing: function() {
        console.debug("QML onClosing signal")
        UiLogic.onCloseWindow()
    }

    title: "AmneziaVPN"

    function gotoPage(type, page, reset, slide) {
        let p_obj;
        if (type === PageType.Basic) p_obj = pageLoader.pages[page]
        else if (type === PageType.Proto) p_obj = protocolPages[page]
        else if (type === PageType.ShareProto) p_obj = sharePages[page]
        else return

        if (pageStackView.depth > 0) {
            pageStackView.currentItem.deactivated()
        }

        if (slide) {
            pageStackView.push(p_obj, {}, StackView.PushTransition)
        } else {
            pageStackView.push(p_obj, {}, StackView.Immediate)
        }

//        if (reset) {
//            p_obj.logic.onUpdatePage();
//        }

        p_obj.activated(reset)
    }

    function closePage() {
        if (pageStackView.depth <= 1) {
            return
        }
        pageStackView.currentItem.deactivated()
        pageStackView.pop()
    }

    function setStartPage(page, slide) {
        if (pageStackView.depth > 0) {
            pageStackView.currentItem.deactivated()
        }

        pageStackView.clear()
        if (slide) {
            pageStackView.push(pages[page], {}, StackView.PushTransition)
        } else {
            pageStackView.push(pages[page], {}, StackView.Immediate)
        }
        if (page === PageEnum.Start) {
            UiLogic.pushButtonBackFromStartVisible = !pageStackView.empty
            UiLogic.onUpdatePage();
        }
    }

    Rectangle {
        anchors.fill: parent
        color: "#0E0E11"
    }

    StackView {
        id: pageStackView
        anchors.fill: parent
        focus: true

        onCurrentItemChanged: function() {
            UiLogic.currentPageValue = currentItem.page
        }

        onDepthChanged: function() {
            UiLogic.pagesStackDepth = depth
        }

        Keys.onPressed: function(event) {
            UiLogic.keyPressEvent(event.key)
            event.accepted = true
        }
    }

    Connections {
        target: UiLogic
        function onGoToPage(page, reset, slide) {
            root.gotoPage(PageType.Basic, page, reset, slide)
        }

        function onGoToProtocolPage(protocol, reset, slide) {
            root.gotoPage(PageType.Proto, protocol, reset, slide)
        }

        function onGoToShareProtocolPage(protocol, reset, slide) {
            root.gotoPage(PageType.ShareProto, protocol, reset, slide)
        }

        function onClosePage() {
            root.closePage()
        }

        function onSetStartPage(page, slide) {
            root.setStartPage(page, slide)
        }

        function onShow() {
            root.show()
        }

        function onHide() {
            root.hide()
        }

        function onRaise() {
            root.show()
            root.raise()
            root.requestActivate()
        }
    }

    PageLoader {
        id: pageLoader

        onFinished: {
            UiLogic.initializeUiLogic()
        }
    }

}
