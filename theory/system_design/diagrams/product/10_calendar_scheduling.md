# Calendar Scheduling System — Architecture Design

## Requirements

### Functional
- Create, update, delete events (one-time and recurring)
- Invite attendees with RSVP (accept, decline, tentative)
- View calendar in day/week/month views
- Free/busy lookup for scheduling across users
- Find available meeting slots across multiple attendees
- Reminders and notifications (email, push, in-app)
- Calendar sharing with permissions (view, edit)
- Recurring events with complex rules (every 2nd Tuesday, last weekday of month)
- CalDAV/iCal sync with external calendars (Google, Outlook)
- Timezone support for global teams
- Room/resource booking

### Non-Functional
- Calendar view load < 200ms (week view, typical user with 20-30 events)
- Free/busy query for 10 attendees over 1 week < 500ms
- Event creation with notifications < 2 seconds
- 99.99% availability (users depend on this for their workday)
- Strong consistency for event creation (no double-booking rooms)
- Eventually consistent for notifications and sync
- Support 500M users, 100M DAU

## Scale Estimates
- 500M users, 100M DAU
- Average user: 5 events/day, 30 events/week
- Calendar reads (view rendering): 500M/day = 25K QPS average
- Event writes: 50M/day = 2K QPS average
- Free/busy queries: 100M/day = 5K QPS average
- Recurring event expansion: the hard part — 1 recurring event can generate 365 instances/year
- Reminder delivery: 5B reminders/day (multiple reminders per event per attendee)

## Architecture Decisions

### Store Recurrence Rules, Not Instances
A recurring event "Weekly team meeting every Monday at 10am forever" would generate infinite instances if materialized. Instead, we store the recurrence rule (RFC 5545 RRULE) and expand instances on-the-fly when querying a date range. The Recurrence Engine takes an RRULE + date range and returns concrete event instances. Exceptions (cancelled occurrences, modified occurrences) are stored separately and merged during expansion. This trades compute at read time for massive storage savings and avoids the "infinite future" problem.

Challenge: editing "this and future events" requires splitting the recurrence rule into two (before and after the split point). This is a well-known complexity of the iCalendar spec.

### Materialized Free/Busy Index
While events are stored with recurrence rules, the Free/Busy Service needs fast answers to "is User X free at 2pm Tuesday?" Expanding recurrence rules for every free/busy query would be too slow. Solution: maintain a materialized free/busy index — a pre-computed bitmap or interval list per user per day. When an event is created/modified/deleted, the free/busy index is updated. For recurring events, we materialize free/busy data for a rolling 90-day window and expand on-demand beyond that. This index is the critical path for scheduling meetings.

### Change Log for Sync
External calendar sync (CalDAV, Google Calendar API) requires knowing "what changed since my last sync." We maintain a per-calendar change log — an append-only sequence of change records (event created, event updated, event deleted, with sequence numbers). Sync clients provide their last-seen sequence number and get all changes since then. This is more efficient than diffing the entire calendar. The change log also enables push notifications via WebSocket for real-time calendar updates.

### Time Zone Handling: Store in UTC, Display in Local
All event times are stored in UTC in the database. The event also stores the creator's timezone (for recurring events, the timezone determines when DST shifts occur). The client converts UTC to the viewer's local timezone for display. This avoids the notorious timezone bugs that plague calendar systems. Exception: "all-day events" are stored as dates (not datetimes) because "Christmas Day" should be December 25th regardless of timezone.

## Component Breakdown

- **Web/Mobile/Desktop Clients**: Calendar rendering (day/week/month), event creation UI, timezone conversion, offline support with sync.
- **CalDAV Gateway**: Implements CalDAV protocol for compatibility with Apple Calendar, Thunderbird, etc. Translates CalDAV operations to internal API calls.
- **WebSocket Gateway**: Real-time calendar updates — when an attendee accepts an invitation or a meeting is moved, all viewers see it immediately.
- **API Gateway**: REST/GraphQL API, auth, rate limiting.
- **Event Service**: CRUD for calendar events. Handles single events, recurring events (RRULE storage), exceptions, and attendee management. Validates timezone correctness.
- **Scheduling Service**: Orchestrates the "find a time" flow. Takes a list of attendees, duration, and date range. Queries Free/Busy for each attendee, computes intersection of available slots, ranks by preference (e.g., prefer mornings, avoid Fridays).
- **Free/Busy Service**: Maintains the materialized free/busy index. Answers "is user X free at time Y" in O(1) via bitmap lookup. Also handles room/resource availability.
- **Recurrence Engine**: Expands RRULEs into concrete instances for a date range. Handles complex rules (BYDAY, BYMONTH, INTERVAL, UNTIL, COUNT) per RFC 5545. Merges exceptions (EXDATE, modified instances).
- **Notification Service**: Manages reminder scheduling. Creates delayed jobs in the Reminder Queue for each reminder (e.g., "notify User A 15 minutes before event X on March 15 at 9:45 AM UTC").
- **Sync Service**: Manages bidirectional sync with external calendars (Google, Outlook). Handles OAuth, webhook subscriptions for external changes, conflict resolution.
- **Reminder Worker**: Polls the Reminder Queue, sends reminders at the scheduled time via email, push notification, or in-app notification.
- **Import Worker**: Bulk import of .ics files. Parses iCalendar format, creates events in batch.
- **Conflict Detector**: Async worker that detects scheduling conflicts (overlapping events) and notifies users. Not blocking — conflicts are warnings, not errors.

## Data Model

### Events (MySQL, sharded by calendar_id)
- PK: event_id
- Columns: calendar_id, organizer_id, title, description, start_time (UTC), end_time (UTC), timezone, location, is_all_day, recurrence_rule (RRULE string), recurrence_end, status (confirmed/tentative/cancelled), created_at, updated_at, version
- Index: (calendar_id, start_time) for range queries

### Recurrence Exceptions (MySQL)
- PK: (event_id, original_start_time)
- Columns: is_cancelled, modified_start_time, modified_end_time, modified_title, modified_description
- Stores individual instance modifications/cancellations

### Attendees (MySQL)
- PK: (event_id, user_id)
- Columns: role (organizer/required/optional), rsvp_status (pending/accepted/declined/tentative), responded_at
- Index: (user_id, event_id) for "my invitations" queries

### Free/Busy Index (Redis or specialized store)
- Key: `freebusy:{user_id}:{date}` (YYYY-MM-DD)
- Value: bitmap of 15-minute slots (96 bits per day) or interval list
- 90-day rolling window materialized, on-demand beyond that

### Calendars (MySQL)
- PK: calendar_id
- Columns: owner_id, name, color, timezone, is_primary, is_shared
- Index: (owner_id)

### Change Log (Cassandra or append-only table)
- Partition key: calendar_id
- Clustering key: sequence_number
- Columns: event_id, change_type (create/update/delete), timestamp, changed_fields

### Reminders (Delay queue / scheduled jobs)
- event_id, user_id, remind_at (UTC), channel (email/push/in-app), status (pending/sent)

## Key Trade-offs

- **Recurrence expansion on read vs materialized instances**: Expanding on read saves storage (no infinite instances) but costs compute on every calendar view. The free/busy index is a hybrid — we materialize for the near future (90 days) but compute on-demand for far future queries. This bounds storage while keeping common queries fast.
- **Strong consistency for room booking vs eventual consistency**: When 10 people try to book the same conference room at 2pm, only one should succeed. This requires pessimistic locking (SELECT FOR UPDATE on the free/busy slot). For personal events, we allow eventual consistency — two conflicting personal events are fine (user resolves manually).
- **CalDAV compatibility vs custom API**: Supporting CalDAV gives compatibility with every calendar client but constrains our data model to iCalendar semantics (which are complex and sometimes surprising). A custom API is simpler but requires custom clients. We support both — CalDAV Gateway translates to our internal model.
- **Timezone storage**: Storing times in the event's local timezone (as CalDAV does) makes display easier but comparison harder. Storing in UTC (our choice) makes comparison easy but requires knowing the original timezone for recurring event DST handling. We store both (UTC + original timezone).

## What Fails First

**Reminder delivery at the top of the hour.** Most reminders are set for round times ("remind me 15 minutes before my 10am meeting" = 9:45am). This creates massive spikes at :45 and :00 past every hour. A system serving 5B reminders/day means ~200M reminders in the 9:45-10:00 window. The Reminder Queue must handle 3M+ dequeue operations per second during this burst. Solutions: (1) Shard the delay queue by user_id hash so no single worker handles the entire burst; (2) Pre-fetch reminders due in the next 5 minutes and batch-deliver; (3) Jitter reminder delivery by +/- 30 seconds to smooth the spike (users won't notice if a reminder is 30s early or late).

## v1 vs v2

### v1 (MVP)
- Single-user calendar with CRUD events
- No recurring events
- Day/week/month view
- Basic email reminders (5 and 15 minutes before)
- PostgreSQL for everything
- No sync, no CalDAV
- Simple free/busy check for one user
- UTC storage, single timezone support

### v2
- Recurring events with full RRULE support
- Multi-attendee scheduling with free/busy intersection
- Materialized free/busy index for fast queries
- CalDAV sync with Google/Outlook
- Room/resource booking with conflict prevention
- WebSocket for real-time calendar updates
- Push notifications and in-app reminders
- Calendar sharing with granular permissions
- "Find a time" smart scheduling assistant
- Change log for efficient incremental sync
- Timezone-aware recurring events with DST handling
- iCal import/export
