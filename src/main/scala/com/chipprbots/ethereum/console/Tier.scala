package com.chipprbots.ethereum.console

/** Where the logo goes at a tier: the "Logo" row of research §3.1. */
enum TierLogo:
  /** None in the dashboard; the FUKUII wordmark on the splash only. */
  case WordmarkOnSplash

  /** None in the dashboard; the 12-line logo on the splash. */
  case SmallLogoOnSplash

  /** The 12-line logo in a right-hand column of the Overview. */
  case SmallLogoInOverview

  /** Whether the dashboard draws the logo at all. */
  def inDashboard: Boolean = this == SmallLogoInOverview

/** The size class of a terminal (spec 027 FR-003, research §3.1). 80×24 is a first-class target, not a fallback.
  *
  *   - S: below 100 columns or below 32 rows (reference 80×24)
  *   - M: 100 to 139 columns and at least 32 rows (reference 120×40)
  *   - L: at least 140 columns and at least 45 rows (reference 160×48)
  */
enum Tier:
  case S, M, L

  /** §3.1's logo row for this tier. */
  def logo: TierLogo = this match
    case S => TierLogo.WordmarkOnSplash
    case M => TierLogo.SmallLogoOnSplash
    case L => TierLogo.SmallLogoInOverview

  /** Track length of a worm row, in slots (the worm takes one): §3.1's 20, 40 and 60 cells. */
  def trackSlots: Int = this match
    case S => 20
    case M => 40
    case L => 60

object Tier:
  val MinMWidth: Int = 100
  val MinMHeight: Int = 32
  val MinLWidth: Int = 140
  val MinLHeight: Int = 45

  def of(width: Int, height: Int): Tier =
    if width >= MinLWidth && height >= MinLHeight then L
    else if width >= MinMWidth && height >= MinMHeight then M
    else S
