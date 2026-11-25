package io.github.stevdrey.realtimevoicelab.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import io.github.stevdrey.realtimevoicelab.realtime.EventInspector.Direction;
import io.github.stevdrey.realtimevoicelab.realtime.EventInspector.Entry;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

public final class EventInspectorController {
    private static final DateTimeFormatter ROW_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final PseudoClass INCOMING = PseudoClass.getPseudoClass("incoming");
    private static final PseudoClass OUTGOING = PseudoClass.getPseudoClass("outgoing");

    private final ObservableList<Entry> rows = FXCollections.observableArrayList();
    private List<Entry> rendered = List.of();

    @FXML
    private TextField eventFilter;

    @FXML
    private ListView<Entry> eventList;

    @FXML
    private Label eventEmpty;

    @FXML
    private TextArea eventDetail;

    @FXML
    private void initialize() {
        var filtered = new FilteredList<>(rows);
        eventList.setItems(filtered);
        eventList.setCellFactory(list -> new EventCell());
        eventList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) ->
                eventDetail.setText(selected == null ? "" : selected.jsonDetail()));
        eventFilter.textProperty().addListener((observable, previous, text) -> {
            var query = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(entry -> matches(entry, query));
            eventEmpty.setText(query.isEmpty() ? "No events yet." : "No matching events.");
        });
    }

    void render(List<Entry> entries) {
        if (rendered == entries) {
            return;
        }
        // Keep retained rows in place so an unrelated batch does not reset the selection.
        int expired = entries.isEmpty() ? -1 : rows.indexOf(entries.getFirst());
        if (expired < 0) {
            rows.setAll(entries);
        } else {
            rows.remove(0, expired);
            rows.addAll(entries.subList(rows.size(), entries.size()));
        }
        rendered = entries;
    }

    private boolean matches(Entry entry, String query) {
        return entry.type().toLowerCase(Locale.ROOT).contains(query)
                || entry.direction().name().toLowerCase(Locale.ROOT).contains(query)
                || (entry.eventId() != null && entry.eventId().toLowerCase(Locale.ROOT).contains(query));
    }

    private static final class EventCell extends ListCell<Entry> {
        private final Tooltip tooltip = new Tooltip();

        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            boolean present = !empty && entry != null;
            boolean incoming = present && entry.direction() == Direction.INCOMING;
            pseudoClassStateChanged(INCOMING, incoming);
            pseudoClassStateChanged(OUTGOING, present && !incoming);
            if (!present) {
                setText(null);
                setAccessibleText(null);
                setTooltip(null);
                return;
            }
            var time = ROW_TIME.format(entry.timestamp());
            var label = (incoming ? "Incoming" : "Outgoing") + " " + time + " " + entry.type();
            var description = entry.eventId() == null ? label : label + " · " + entry.eventId();
            setText(time + (incoming ? " ↓ " : " ↑ ") + entry.type());
            setAccessibleText(description);
            tooltip.setText(description);
            setTooltip(tooltip);
        }
    }
}
