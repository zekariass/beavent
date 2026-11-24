package com.beavent.backend.crm.internal;

import static com.beavent.db.crm.Tables.CUSTOMERS;

import com.beavent.backend.platform.ApiException;
import com.beavent.backend.platform.Ids;
import com.beavent.backend.platform.ProblemCode;
import com.beavent.backend.tenancy.OrgAccess;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Importing a client list (P-13): a CSV file with a header row (name, or given name and father's name; phone; email —
 * English or Amharic headings, and the column names other booking systems export). First a preview, then the same
 * call with {@code save} adds the new clients. A phone number the business already has is left alone.
 */
@Component
public class ClientImport {

    public static final int MAX_ROWS = 5000;
    /** Rows returned besides errors (the preview shows the start of the file). */
    static final int SHOWN_ROWS = 100;

    static final int SHOWN_ERRORS = 500;

    private static final Map<String, String> HEADINGS = Map.ofEntries(
            Map.entry("name", "name"),
            Map.entry("full name", "name"),
            Map.entry("client", "name"),
            Map.entry("client name", "name"),
            Map.entry("customer", "name"),
            Map.entry("customer name", "name"),
            Map.entry("ስም", "name"),
            Map.entry("ሙሉ ስም", "name"),
            Map.entry("first name", "given"),
            Map.entry("given name", "given"),
            Map.entry("first", "given"),
            Map.entry("የመጀመሪያ ስም", "given"),
            Map.entry("last name", "father"),
            Map.entry("father's name", "father"),
            Map.entry("fathers name", "father"),
            Map.entry("surname", "father"),
            Map.entry("የአባት ስም", "father"),
            Map.entry("phone", "phone"),
            Map.entry("phone number", "phone"),
            Map.entry("mobile", "phone"),
            Map.entry("mobile number", "phone"),
            Map.entry("telephone", "phone"),
            Map.entry("ስልክ", "phone"),
            Map.entry("ስልክ ቁጥር", "phone"),
            Map.entry("email", "email"),
            Map.entry("e-mail", "email"),
            Map.entry("email address", "email"),
            Map.entry("ኢሜይል", "email"));

    private final org.jooq.DSLContext dsl;
    private final TransactionTemplate transactions;

    ClientImport(org.jooq.DSLContext dsl, TransactionTemplate transactions) {
        this.dsl = dsl;
        this.transactions = transactions;
    }

    public ClientBodies.ImportResult run(OrgAccess access, String csv, boolean save) {
        access.require(Clients.MANAGE).require(Clients.CONTACT);
        List<List<String>> table = parse(csv == null ? "" : csv);
        if (table.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The file is empty.");
        }
        List<String> header = table.getFirst();
        int name = -1, given = -1, father = -1, phone = -1, email = -1;
        for (int i = 0; i < header.size(); i++) {
            String role =
                    HEADINGS.get(header.get(i).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
            if (role == null) continue;
            switch (role) {
                case "name" -> name = name < 0 ? i : name;
                case "given" -> given = given < 0 ? i : given;
                case "father" -> father = father < 0 ? i : father;
                case "phone" -> phone = phone < 0 ? i : phone;
                case "email" -> email = email < 0 ? i : email;
                default -> {}
            }
        }
        if (name < 0 && given < 0) {
            throw new ApiException(
                    ProblemCode.VALIDATION_FAILED,
                    "The first row must name the columns, for example: name, phone, email.");
        }
        if (table.size() - 1 > MAX_ROWS) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Import up to " + MAX_ROWS + " clients at a time.");
        }
        final int nameAt = name, givenAt = given, fatherAt = father, phoneAt = phone, emailAt = email;
        return transactions.execute(status -> {
            Set<String> known = new HashSet<>(dsl.select(CUSTOMERS.PHONE_E164)
                    .from(CUSTOMERS)
                    .where(CUSTOMERS.STATUS.eq("ACTIVE"), CUSTOMERS.PHONE_E164.isNotNull())
                    .fetch(CUSTOMERS.PHONE_E164));
            List<ClientBodies.ImportRow> rows = new ArrayList<>();
            int created = 0, existing = 0, errors = 0;
            for (int r = 1; r < table.size(); r++) {
                List<String> cells = table.get(r);
                if (cells.stream().allMatch(String::isBlank)) continue;
                int line = r + 1;
                String fullName = nameAt >= 0
                        ? cell(cells, nameAt)
                        : (cell(cells, givenAt) + " " + cell(cells, fatherAt)).strip();
                String typedPhone = cell(cells, phoneAt);
                String typedEmail = cell(cells, emailAt);
                // What is wrong, as a code the screen words in the reader's language.
                String problem = null;
                String cleanName = null, cleanPhone = null, cleanEmail = null;
                try {
                    cleanName = Clients.name(fullName);
                } catch (ApiException e) {
                    problem = "NAME";
                }
                try {
                    cleanPhone = Clients.phone(typedPhone);
                } catch (ApiException e) {
                    problem = problem == null ? "PHONE" : problem;
                }
                try {
                    cleanEmail = Clients.email(typedEmail);
                } catch (ApiException e) {
                    problem = problem == null ? "EMAIL" : problem;
                }
                String outcome;
                if (problem != null) {
                    outcome = "ERROR";
                    errors++;
                } else if (cleanPhone != null && !known.add(cleanPhone)) {
                    outcome = "EXISTING";
                    existing++;
                } else {
                    outcome = "NEW";
                    created++;
                    if (save) {
                        dsl.insertInto(CUSTOMERS)
                                .set(CUSTOMERS.ID, Ids.newId())
                                .set(CUSTOMERS.ORG_ID, access.orgId())
                                .set(CUSTOMERS.DISPLAY_NAME, cleanName)
                                .set(CUSTOMERS.PHONE_E164, cleanPhone)
                                .set(CUSTOMERS.EMAIL, cleanEmail)
                                .set(CUSTOMERS.SOURCE, "IMPORT")
                                .execute();
                    }
                }
                boolean show = outcome.equals("ERROR") ? errors <= SHOWN_ERRORS : rows.size() < SHOWN_ROWS;
                if (show) {
                    rows.add(new ClientBodies.ImportRow(
                            line,
                            problem == null ? cleanName : fullName,
                            problem == null ? cleanPhone : typedPhone,
                            problem == null ? cleanEmail : typedEmail,
                            outcome,
                            problem));
                }
            }
            return new ClientBodies.ImportResult(save, created, existing, errors, rows);
        });
    }

    private static String cell(List<String> cells, int at) {
        return at < 0 || at >= cells.size() ? "" : cells.get(at).strip();
    }

    /** CSV as spreadsheets write it: quotes, doubled quotes, line breaks inside quotes; comma, semicolon or tab. */
    static List<List<String>> parse(String text) {
        String csv = text.startsWith("﻿") ? text.substring(1) : text;
        int firstBreak = csv.indexOf('\n');
        String firstLine = firstBreak < 0 ? csv : csv.substring(0, firstBreak);
        char separator = ',';
        long commas = firstLine.chars().filter(c -> c == ',').count();
        if (firstLine.chars().filter(c -> c == ';').count() > commas) separator = ';';
        if (firstLine.chars().filter(c -> c == '\t').count() > commas) separator = '\t';

        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == separator) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
