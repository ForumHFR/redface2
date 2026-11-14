package fr.forumhfr.redface2.feature.messages

/** The draft session reads live state and reports changes through its ViewModel. */
internal class PrivateMessageDraftState<State>(
    val read: () -> State,
    val update: ((State) -> State) -> Unit,
)
