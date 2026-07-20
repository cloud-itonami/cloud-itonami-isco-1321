(ns manufacturing-floor.store
  "SSoT for the ISCO-08 1321 independent manufacturing-floor sole-proprietor
  actor, behind a `Store` protocol so the backend is a swap (MemStore
  default ‖ a real Datomic/kotoba-server backend, per the itonami actor
  pattern).

  Domain = independent manufacturing floor management:

    line        — a production line (lineId, name)
    work-order  — a production run scoped to a line (orderId, lineId, spec)
    qc-check    — a quality check against a work order (checkId, orderId,
                  result #{:pass :fail})
    incident    — a floor incident (incidentId, lineId, severity)
    human-gap   — a human-required automation gap + its referral draft
                  (ADR-2607202500): this actor's OWN half of the record
                  only (gap detected, draft-id, target-actor named). It
                  never writes to, or calls, any other actor's store.

  The append-only records are the operating ledger: a qc-check must
  reference a registered work order on a registered line, and qc-checks/
  incidents/human-gaps are never mutated in place, only appended.")

(defprotocol Store
  (line [st line-id])
  (work-order [st order-id])
  (work-orders-of [st line-id])
  (qc-checks-of [st order-id])
  (incidents-of [st line-id])
  (human-gaps-of [st line-id])
  (register-line! [st line])
  (register-work-order! [st work-order])
  (record-qc-check! [st qc-check])
  (record-incident! [st incident])
  (record-human-gap! [st human-gap]))

(defrecord MemStore [state]
  Store
  (line [_ line-id]
    (get-in @state [:lines line-id]))
  (work-order [_ order-id]
    (get-in @state [:work-orders order-id]))
  (work-orders-of [_ line-id]
    (filter #(= line-id (:line-id %)) (vals (:work-orders @state))))
  (qc-checks-of [_ order-id]
    (filter #(= order-id (:order-id %)) (:qc-checks @state)))
  (incidents-of [_ line-id]
    (filter #(= line-id (:line-id %)) (:incidents @state)))
  (human-gaps-of [_ line-id]
    (filter #(= line-id (:line-id %)) (:human-gaps @state)))
  (register-line! [_ line]
    (swap! state assoc-in [:lines (:line-id line)] line))
  (register-work-order! [_ work-order]
    (swap! state assoc-in [:work-orders (:order-id work-order)] work-order))
  (record-qc-check! [_ qc-check]
    (swap! state update :qc-checks (fnil conj []) qc-check))
  (record-incident! [_ incident]
    (swap! state update :incidents (fnil conj []) incident))
  (record-human-gap! [_ human-gap]
    (swap! state update :human-gaps (fnil conj []) human-gap)))

(defn mem-store
  ([] (mem-store {}))
  ([seed]
   (->MemStore (atom (merge {:lines {} :work-orders {} :qc-checks [] :incidents [] :human-gaps []} seed)))))
