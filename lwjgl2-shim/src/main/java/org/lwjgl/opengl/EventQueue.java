package org.lwjgl.opengl;

import java.nio.ByteBuffer;

/**
 * Fixed-size synchronized event ring used by the LWJGL2-compatible input
 * backend. Events are fixed-width byte records; producers (GLFW callbacks on
 * the main thread) append via {@link #putEvent}, and the Keyboard/Mouse facades
 * drain via {@link #copyEvents} on poll.
 *
 * The buffer is kept in "fill" mode (position = write cursor, limit = capacity)
 * between puts. copyEvents flips to drain, moves as many whole events as the
 * destination can hold, then compacts the remainder back to the front so
 * partially-drained data survives to the next read.
 *
 * Independently re-derived to match the classic LWJGL2 java event-queue ABI.
 */
public class EventQueue {

    /** Capacity in events. */
    private static final int QUEUE_SIZE = 200;

    /** Size in bytes of a single event record. */
    private final int eventSize;

    /** Backing store, held in fill mode between puts. */
    private final ByteBuffer queue;

    public EventQueue(int eventSize) {
        this.eventSize = eventSize;
        this.queue = ByteBuffer.allocate(QUEUE_SIZE * eventSize);
    }

    /** Size of a single event record in bytes. */
    public int getEventSize() {
        return eventSize;
    }

    /** Discard all pending events. */
    public synchronized void clearEvents() {
        queue.clear();
    }

    /**
     * Append one event. The supplied buffer must have exactly {@code eventSize}
     * bytes remaining. If the queue is full the event is dropped.
     *
     * @return true if the event was stored, false if the queue was full
     */
    public synchronized boolean putEvent(ByteBuffer event) {
        if (event.remaining() != eventSize) {
            throw new IllegalArgumentException(
                "Internal error: event size " + eventSize
                + " does not equal the supplied event size " + event.remaining());
        }
        if (queue.remaining() >= eventSize) {
            queue.put(event);
            return true;
        }
        return false;
    }

    /**
     * Drain pending events into {@code dest}. Copies at most as many whole
     * bytes as {@code dest} can hold; any surplus is compacted to the front of
     * the queue for the next call.
     */
    public synchronized void copyEvents(ByteBuffer dest) {
        queue.flip();
        int oldLimit = queue.limit();
        if (dest.remaining() < queue.remaining()) {
            queue.limit(dest.remaining() + queue.position());
        }
        dest.put(queue);
        queue.limit(oldLimit);
        queue.compact();
    }

    /**
     * Return a writable slice positioned at the start of the most recently
     * stored event (the record immediately before the current write cursor).
     * Used to merge a translated character into the preceding key-down event so
     * a single LWJGL2 event carries both key code and character, matching the
     * classic key/char pairing. If the queue is empty the slice is positioned
     * at offset 0 and callers must guard against reading stale data.
     */
    public synchronized ByteBuffer getLastEvent() {
        int position = queue.position();
        // No complete prior event buffered -> nothing to return. Returning a
        // slice at offset 0 here would expose stale (never-zeroed) bytes and
        // cause postCharacter to "merge" a standalone char into garbage, silently
        // dropping the typed character.
        if (position < eventSize) {
            return null;
        }
        queue.position(position - eventSize);
        ByteBuffer slice = queue.slice();
        slice.clear();
        queue.position(position);
        return slice;
    }
}