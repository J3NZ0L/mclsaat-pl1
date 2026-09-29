package hu.mclsaat.legacy.catalog.web;

import hu.mclsaat.legacy.catalog.domain.PlanRow;
import hu.mclsaat.legacy.catalog.domain.SubscriberRow;
import hu.mclsaat.legacy.catalog.domain.SubscriptionRow;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * Wire shapes for subsystem 1.
 *
 * <p>These deliberately leak the database's own vocabulary: {@code custNo} as a zero-padded
 * string, {@code statusCode} as {@code "AC"}, {@code monthlyFeeMinor} in fillér,
 * {@code dataAllowanceMb} in megabytes, {@code activeFlag} as {@code "Y"}. A well-behaved
 * modern API would normalise all of that; a legacy one hands the caller the raw column and
 * expects it to know. Subsystem 2 has to translate every one of these fields.
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record PlanView(
            String planCode,
            String serviceKind,
            String displayName,
            String planKind,
            String addonCategory,
            long monthlyFeeMinor,
            String currency,
            int dataAllowanceMb,
            Integer speedKbps,
            String activeFlag) {

        static PlanView of(PlanRow row) {
            return new PlanView(row.planCode(), String.valueOf(row.serviceKind()), row.displayName(),
                    row.planKind(), row.addonCategory(), row.monthlyFeeMinor(), "HUF",
                    row.dataAllowanceMb(), row.speedKbps(), row.active() ? "Y" : "N");
        }
    }

    public record SubscriberView(
            String custNo,
            String fullName,
            String email,
            String msisdn,
            String createdTs) {

        static SubscriberView of(SubscriberRow row) {
            return new SubscriberView(row.custNo(), row.fullName(), row.email(), row.msisdn(),
                    row.createdTs().toString());
        }
    }

    public record SubscriptionView(
            String subId,
            String custNo,
            String planCode,
            String statusCode,
            LocalDate activatedOn,
            String msisdn,
            String simIccid,
            int dataAllowanceMb,
            String parentSubId,
            String createdTs,
            String updatedTs) {

        static SubscriptionView of(SubscriptionRow row) {
            return new SubscriptionView(row.subId(), row.custNo(), row.planCode(), row.statusCode(),
                    row.activatedOn(), row.msisdn(), row.simIccid(), row.dataAllowanceMb(),
                    row.parentSubId(), row.createdTs().toString(), row.updatedTs().toString());
        }
    }

    public record CreateSubscriberRequest(
            @NotBlank String fullName,
            @NotBlank String email,
            @Pattern(regexp = "^[0-9]{8,15}$|^$", message = "msisdn must be 8-15 digits without '+'")
            String msisdn) {
    }

    public record ReserveSubscriptionRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{8}$", message = "custNo must be exactly 8 digits")
            String custNo,
            @NotBlank String planCode,
            String msisdn) {
    }

    public record ReserveAddonRequest(
            @NotBlank String addonPlanCode) {
    }

    public record ActivateRequest(
            String simIccid,
            LocalDate activatedOn) {
    }

    public record ChangePlanRequest(
            @NotBlank String newPlanCode) {
    }

    public record ApiError(String code, String message) {
    }
}
