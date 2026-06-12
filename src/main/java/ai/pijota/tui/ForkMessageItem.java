package ai.pijota.tui;

import ai.pijota.messages.Message;

record ForkMessageItem(int number, Message.User message) implements SelectorItem {
    @Override
    public String searchText() {
        return number + " " + message.content();
    }

    @Override
    public String titleText() {
        return "[" + number + "]";
    }

    @Override
    public String detailText() {
        return message.content().replace('\n', ' ').trim();
    }
}
