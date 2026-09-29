package hu.mclsaat.legacy.catalog.web;

import hu.mclsaat.legacy.catalog.repo.PlanRepository;
import hu.mclsaat.legacy.catalog.service.CatalogException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service 1: browse plans and tariffs. */
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanRepository plans;

    public PlanController(PlanRepository plans) {
        this.plans = plans;
    }

    /**
     * @param serviceKind    {@code I} (internet) or {@code M} (mobile)
     * @param planKind       {@code BASE} or {@code ADDON}
     * @param addonCategory  {@code DATA_PACK} or {@code ROAMING}
     * @param includeWithdrawn include plans whose {@code activeFlag} is {@code N}
     */
    @GetMapping
    public List<CatalogDtos.PlanView> list(
            @RequestParam(required = false) String serviceKind,
            @RequestParam(required = false) String planKind,
            @RequestParam(required = false) String addonCategory,
            @RequestParam(defaultValue = "false") boolean includeWithdrawn) {

        Character kind = null;
        if (serviceKind != null && !serviceKind.isBlank()) {
            if (serviceKind.length() != 1 || "IM".indexOf(serviceKind.charAt(0)) < 0) {
                throw new CatalogException.BadRequest("serviceKind must be 'I' or 'M', was: " + serviceKind);
            }
            kind = serviceKind.charAt(0);
        }
        return plans.search(kind, emptyToNull(planKind), emptyToNull(addonCategory), includeWithdrawn)
                .stream()
                .map(CatalogDtos.PlanView::of)
                .toList();
    }

    @GetMapping("/{planCode}")
    public CatalogDtos.PlanView byCode(@PathVariable String planCode) {
        return plans.findByCode(planCode)
                .map(CatalogDtos.PlanView::of)
                .orElseThrow(() -> new CatalogException.NotFound("plan", planCode));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
