(ns timechamp.filldays
  (:gen-class)
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [clojure.data.json :as json]
            [schema.core :as s]
            [timechamp.businesstime :as bt]
            [timechamp.gc-util :as gc]
            [timechamp.tc-util :as tc])
  (:import [java.time DayOfWeek LocalDate]
           timechamp.schema.CanonicalEvent))

;; TODO with ^:const, get error:
;; Can't embed object in code, maybe print-dup not defined: SATURDAY
(def ^:private WEEKEND  #{DayOfWeek/SATURDAY DayOfWeek/SUNDAY})

(defn ^:private pct-pair? [[task-id time-str]]
  (some? (re-find bt/PCT_RE time-str)))

(defn ^:private task-id->time-from-arguments
  "Return a map with task-id keys and minute (for absolute durations input)
   or float (for percent input) values."
  [arguments]
  (let [args-map (apply hash-map arguments) ; create pairs {id->arg...}
        grouped-args (group-by pct-pair? args-map) ; {bool->[[id arg]...]}
        ; grouped-args-maps (fmap #(into {} %) grouped-args) ; {bool->{id->arg}}
        pre-task-id->minutes (into {} (grouped-args false))
        pre-task-id->pcts (into {} (grouped-args true))]
    {:task-id->minutes (fmap bt/hours-str-to-minutes pre-task-id->minutes)
     :task-id->pcts (fmap bt/pct-strs-to-num pre-task-id->pcts)}))

(s/defn ^:private event-day :- LocalDate
  "Return the start day of the event."
  [event :- CanonicalEvent]
  (.toLocalDate (:start-time event)))

(defn ^:private weekend-filter
  "Returns a filter that excludes weekend days if `include-weekends` is false,
  or excludes nothing"
  [include-weekends?]
  (if include-weekends?
    (constantly true)
    (s/fn [date :- LocalDate]
      (not (contains? WEEKEND (.getDayOfWeek date))))))

(s/defn ^:private days-between-start-end :- [LocalDate]
  "Return a sequence of dates between start-date and end-date, inclusive.
  Optionally exclude any weekend days according to the third `include-weekends?`
  argument if provided."
  ([start-date :- LocalDate end-date :- LocalDate]
   ;; there's literally got to be a better way,
   ;; but I just cannot be arsed right now
   (if (= start-date end-date)
     (list start-date)
     (let [tomorrow (.plusDays start-date 1)]
       (cons start-date (days-between-start-end tomorrow end-date)))))

  ([start-date :- LocalDate end-date :- LocalDate include-weekends? :- Boolean]
   (filter (weekend-filter include-weekends?)
           (days-between-start-end start-date end-date))))

(defn ^:private create-day-event-map
  "Return a map of day -> [events], excluding any events on days not in
  `days-covered`. The map will contain keys for every day in `days-covered`,
  mapped to an empty list if there are no events on that day.

  Events crossing midnight into a day not included in `days-covered` will be
  truncated at midnight."
  [events days-covered]
  (let [days->default-events (reduce #(assoc %1 %2 []) {} days-covered)]
    (as-> events e
      (mapcat bt/split-event-at-midnight e)
      (group-by event-day e)
      ;; at least {day -> []} for every day under consideration
      (merge days->default-events e)
      ;; keep only those in days-covered
      ;; TODO Interestingly, this can chop off an event if it crosses midnight
      ;; into a day that's not part of days-covered. Should it?
      (select-keys e days-covered))))

(defn ^:private add-user-times-to-day
  "Creates events on the provided date according to the supplied times and
  returns a new list of events with new events added. Moves the provided events
  to the beginning of the day, though hopefully this will change in the future.
  When provided the two user time arguments and minutes worked, returns this
  function with those arguments partially applied"

  ;; TODO Doesn't use the localdate parameter, which makes the pain in fill-days
  ;; pointless at the moment. It'll need it for future features. We could glean
  ;; the date from the events, but that felt hacky (I'm on the fence about this)

  ([task-id->minutes task-id->pcts minutes-worked]
   #(add-user-times-to-day %1 %2 task-id->minutes task-id->pcts minutes-worked))
  ([localdate events task-id->minutes task-id->pcts minutes-worked]
   (->> events
        (sort-by :start-time)  ; don't assume it's sorted?
        (reduce bt/slam-to-earliest [])  ; move events to beginning of the day
        (bt/add-minutes-to-day task-id->minutes)
        (bt/add-pcts-to-day task-id->pcts minutes-worked))))

(defn ^:private fill-days
  "Create events to fill out the values in day->events according to the
  provided user times."
  [day->events task-id->minutes task-id->pcts minutes-worked]
  ;; This function applies add-user-times-to-day to the values of the map, which
  ;; requires date. We can't use fmap because the dates are the map keys (which
  ;; aren't passed to fmap), so map a function that takes and returns [key
  ;; new-value] and turn those back into a map
  (let [partial-add-times-to-day (add-user-times-to-day task-id->minutes
                                                        task-id->pcts
                                                        minutes-worked)]
        (->> day->events
             (map (fn [[k v]] [k (partial-add-times-to-day k v)]))
             (into {})))
  #_(->>
     events
     (mapcat bt/split-event-at-midnight)
     (group-by event-day)
     (merge (reduce #(assoc %1 %2 []) {} days-covered))
     (#(map % days-covered)) ; keep only those in days-covered, list of lists
     (map #(sort-by :start-time))
     (map #(bt/add-minutes-to-day % task-id->minutes))
     (map #(fill-day-by-percents % task-id->pcts minutes-worked))
     vals
     flatten)
  )

(s/defn transfer-gc-to-tc :- {:message s/Str :ok? (s/maybe s/Bool)}
  "Pull events from Google Calendar and process them into TimeCamp.
  Arguments:
    start-date          First date to pull events from.
    end-date            Last date to pull events from.
    calendar-id         ID of the calendar to pull events from.
    gc-secrets-file     Path to the secrets file for Google OAuth
    data-store-dir      Path to the directory in which to store OAuth creds
    tc-api-token        API token for TimeCamp
    hours-worked        Number of hours worked each day
    include-weekends?   Boolean indicating whether to process weekend events.
    arguments           Sequence of unparsed Task ID/Time tuples

  Return a map containing :message, describing how many tasks failed and
  succeeded, and optionally :ok?, true if all tasks succeeded."
  [start-date :- LocalDate
   end-date :- LocalDate
   calendar-id :- s/Str
   gc-secrets-file :- s/Str
   data-store-dir :- s/Str
   tc-api-token :- s/Str
   hours-worked :- s/Num
   include-weekends? :- s/Bool
   arguments :- [s/Str]]
  (let [{:keys [task-id->minutes task-id->pcts]}
        (task-id->time-from-arguments arguments)

        minutes-worked (bt/round (* hours-worked 60))

        days-covered (days-between-start-end start-date end-date
                                             include-weekends?)

        start-datetime (bt/first-second-of-date start-date)

        end-datetime (bt/last-second-of-date end-date)

        events (gc/get-events gc-secrets-file data-store-dir
                              calendar-id start-datetime end-datetime)

        day->events (create-day-event-map events days-covered)

        day->filled-events (fill-days day->events
                                      task-id->minutes
                                      task-id->pcts
                                      minutes-worked)

        all-events (flatten (vals day->filled-events))

        ;; map, doesn't appear we can batch post events
        ;; TODO rate limit?
        posted-days (map #(tc/summary-post-event-to-tc tc-api-token %)
                         all-events)

        {successes true failures false} (group-by :ok? posted-days)

        ok? (empty? failures)]

    ;; TODO log failures as they happen, or quit on error
    (when (not ok?) (json/pprint failures))
    {:message
     (format "%d successes, %d failures" (count successes) (count failures))
     :ok? ok?}))
