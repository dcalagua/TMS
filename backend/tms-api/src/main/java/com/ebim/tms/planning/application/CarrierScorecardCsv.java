package com.ebim.tms.planning.application;

import com.ebim.tms.planning.application.CarrierScorecardView.CarrierRow;
import com.ebim.tms.planning.application.CarrierScorecardView.CostFigures;
import com.ebim.tms.planning.application.CarrierScorecardView.InvoiceFigures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The scorecard as a table: what {@code GET /reporting/carrier-scorecard/export} writes.
 *
 * <p><b>Two kinds of row, and the {@code currency} column says which.</b> A row with an empty
 * currency is a carrier's operation - trips, punctuality, deliveries, tenders, documents - and its
 * money cells are empty. A row with a currency is that carrier's money in that currency - invoices
 * and cost - and its operational cells are empty. That is the only shape in which a spreadsheet can
 * both sum the trips column (each carrier counted once) and keep two currencies apart (TMS converts
 * nothing): repeating the operation on every currency row would double the trips of a carrier paid in
 * two currencies.
 *
 * <p>Columns the caller may not see are <b>left out of the header</b>, not written empty: an empty
 * cell means "nothing measured", which is a different statement from "not yours to see". Headers are
 * the JSON field names, untranslated, as the KPI export's are.
 */
record CarrierScorecardCsv(List<String> header, List<List<String>> rows) {

    private static final List<String> OPERATION = List.of(
            "carrierKey", "carrierCode", "carrierName", "ownFleet", "currency",
            "trips", "tripsCancelled", "tripsExecuted", "tripsCompleted",
            "departuresMeasured", "departuresLate", "onTimeDeparturePercent",
            "serviceWindowsMeasured", "serviceWindowsMissed", "inWindowPercent",
            "deliveriesRecorded", "deliveriesDelivered", "deliveriesShort", "deliveriesNotAttempted",
            "deliverySuccessPercent", "exceptions", "exceptionsPer100Trips");
    private static final List<String> TENDERS = List.of(
            "tendersOffered", "tendersAccepted", "tendersRejected", "tendersExpired", "tenderAcceptancePercent",
            "tenderAverageResponseMinutes");
    private static final List<String> DOCUMENTS = List.of(
            "documents", "documentsDelivered", "documentsReturned", "documentsDeliveredPercent",
            "cargoReturnPercent");
    private static final List<String> INVOICES = List.of(
            "invoices", "invoicesWithDiscrepancy", "invoiceDiscrepancyPercent", "invoiceAmountObserved");
    private static final List<String> COST = List.of(
            "tripsCosted", "tripsWithActualCost", "costAmount", "costPerTrip", "costWeightKg", "costPerKg");

    static CarrierScorecardCsv of(CarrierScorecardView view) {
        List<String> header = new ArrayList<>(OPERATION);
        if (view.tendersVisible()) {
            header.addAll(TENDERS);
        }
        if (view.documentsVisible()) {
            header.addAll(DOCUMENTS);
        }
        if (view.invoicesVisible()) {
            header.addAll(INVOICES);
        }
        if (view.costVisible()) {
            header.addAll(COST);
        }

        List<List<String>> rows = new ArrayList<>();
        for (CarrierRow carrier : view.carriers()) {
            List<String> operation = identity(carrier, "");
            operation.addAll(cells(n(carrier.trips()), n(carrier.tripsCancelled()), n(carrier.tripsExecuted()),
                    n(carrier.tripsCompleted()), n(carrier.departuresMeasured()), n(carrier.departuresLate()),
                    d(carrier.onTimeDeparturePercent()), n(carrier.serviceWindowsMeasured()),
                    n(carrier.serviceWindowsMissed()), d(carrier.inWindowPercent()), n(carrier.deliveriesRecorded()),
                    n(carrier.deliveriesDelivered()), n(carrier.deliveriesShort()),
                    n(carrier.deliveriesNotAttempted()), d(carrier.deliverySuccessPercent()),
                    n(carrier.exceptions()), d(carrier.exceptionsPer100Trips())));
            if (view.tendersVisible()) {
                var tenders = carrier.tenders();
                operation.addAll(cells(n(tenders.offered()), n(tenders.accepted()), n(tenders.rejected()),
                        n(tenders.expired()), d(tenders.acceptancePercent()), d(tenders.averageResponseMinutes())));
            }
            if (view.documentsVisible()) {
                var documents = carrier.documents();
                operation.addAll(cells(n(documents.documents()), n(documents.delivered()),
                        n(documents.returned()), d(documents.deliveredPercent()), d(documents.cargoReturnPercent())));
            }
            pad(operation, header.size());
            rows.add(operation);

            Map<String, List<String>> money = new TreeMap<>();
            int moneyStart = OPERATION.size() + (view.tendersVisible() ? TENDERS.size() : 0)
                    + (view.documentsVisible() ? DOCUMENTS.size() : 0);
            if (view.invoicesVisible()) {
                for (InvoiceFigures invoice : carrier.invoices()) {
                    List<String> row = money.computeIfAbsent(invoice.currency(), currency -> blank(carrier, currency,
                            header.size()));
                    set(row, moneyStart, cells(n(invoice.invoices()), n(invoice.withDiscrepancy()),
                            d(invoice.discrepancyRatePercent()), d(invoice.amountObserved())));
                }
            }
            if (view.costVisible()) {
                int costStart = moneyStart + (view.invoicesVisible() ? INVOICES.size() : 0);
                for (CostFigures cost : carrier.cost()) {
                    List<String> row = money.computeIfAbsent(cost.currency(), currency -> blank(carrier, currency,
                            header.size()));
                    set(row, costStart, cells(n(cost.tripsCosted()), n(cost.tripsWithActual()), d(cost.amount()),
                            d(cost.costPerTrip()), d(cost.weightKg()), d(cost.costPerKg())));
                }
            }
            rows.addAll(money.values());
        }
        return new CarrierScorecardCsv(List.copyOf(header), rows);
    }

    private static List<String> identity(CarrierRow carrier, String currency) {
        List<String> row = new ArrayList<>();
        row.add(carrier.carrierKey());
        row.add(carrier.carrierCode());
        row.add(carrier.carrierName());
        row.add(Boolean.toString(carrier.ownFleet()));
        row.add(currency);
        return row;
    }

    private static List<String> blank(CarrierRow carrier, String currency, int width) {
        List<String> row = identity(carrier, currency);
        pad(row, width);
        return row;
    }

    private static void pad(List<String> row, int width) {
        while (row.size() < width) {
            row.add(null);
        }
    }

    private static void set(List<String> row, int start, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            row.set(start + index, values.get(index));
        }
    }

    /** A list of cells that may hold nulls (an empty cell), which {@code List.of} refuses. */
    private static List<String> cells(String... values) {
        return Arrays.asList(values);
    }

    private static String n(long value) {
        return Long.toString(value);
    }

    /** Dot-decimal, never scientific notation; empty when nothing was measured. */
    private static String d(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
