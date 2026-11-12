import { describe, expect, it } from "vitest";
import { addDays, clampRange, lastDays, rangeDays } from "./dateRange";

describe("dateRange", () => {
  it("counts both ends and crosses months and years", () => {
    expect(rangeDays("2026-11-06", "2026-11-12")).toBe(7);
    expect(rangeDays("2026-11-12", "2026-11-12")).toBe(1);
    expect(addDays("2026-12-30", 3)).toBe("2027-01-02");
    expect(addDays("2026-03-01", -1)).toBe("2026-02-28");
  });

  it("builds the last N days ending today", () => {
    expect(lastDays(7, "2026-11-12")).toEqual({ from: "2026-11-06", to: "2026-11-12" });
    expect(lastDays(30, "2026-11-12")).toEqual({ from: "2026-10-14", to: "2026-11-12" });
  });

  it("drags the other end instead of exceeding the maximum", () => {
    expect(clampRange({ from: "2026-09-01", to: "2026-11-12" }, "from", 30)).toEqual({ from: "2026-09-01", to: "2026-09-30" });
    expect(clampRange({ from: "2026-11-06", to: "2026-12-20" }, "to", 30)).toEqual({ from: "2026-11-21", to: "2026-12-20" });
    expect(clampRange({ from: "2026-11-01", to: "2026-11-30" }, "to", 30)).toEqual({ from: "2026-11-01", to: "2026-11-30" });
  });

  it("keeps the ends in order", () => {
    expect(clampRange({ from: "2026-11-20", to: "2026-11-12" }, "from")).toEqual({ from: "2026-11-20", to: "2026-11-20" });
    expect(clampRange({ from: "2026-11-06", to: "2026-11-01" }, "to")).toEqual({ from: "2026-11-01", to: "2026-11-01" });
    expect(clampRange({ from: "", to: "2026-11-01" }, "to", 30)).toEqual({ from: "", to: "2026-11-01" });
  });
});
