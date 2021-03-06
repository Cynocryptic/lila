package lila.mod

import lila.common.{ IpAddress, EmailAddress }
import lila.report.{ Mod, Suspect, Room }
import lila.security.Permission
import lila.security.{ Firewall, UserSpy, Store => SecurityStore }
import lila.user.{ User, UserRepo, LightUserApi }

final class ModApi(
    logApi: ModlogApi,
    userSpy: User.ID => Fu[UserSpy],
    firewall: Firewall,
    reporter: akka.actor.ActorSelection,
    reportApi: lila.report.ReportApi,
    notifier: ModNotifier,
    lightUserApi: LightUserApi,
    refunder: RatingRefund,
    lilaBus: lila.common.Bus
) {

  def setEngine(mod: Mod, prev: Suspect, v: Boolean): Funit = (prev.user.engine != v) ?? {
    for {
      _ <- UserRepo.setEngine(prev.user.id, v)
      sus = prev.set(_.copy(engine = v))
      _ <- reportApi.process(mod, sus, Set(Room.Cheat, Room.Print))
      _ <- logApi.engine(mod, sus, v)
    } yield {
      lilaBus.publish(lila.hub.actorApi.mod.MarkCheater(sus.user.id, v), 'adjustCheater)
      if (v) {
        notifier.reporters(mod, sus)
        refunder schedule sus
      }
    }
  }

  private[mod] def autoAdjust(username: String): Funit = for {
    sus <- reportApi.getSuspect(username) flatten s"No such suspect $username"
    unengined <- logApi.wasUnengined(sus)
    _ <- if (unengined) funit else reportApi.getLichess flatMap {
      _ ?? { lichess =>
        lila.mon.cheat.autoMark.count()
        setEngine(lichess, sus, true)
      }
    }
  } yield ()

  def setBooster(mod: Mod, prev: Suspect, v: Boolean): Funit = (prev.user.booster != v) ?? {
    for {
      _ <- UserRepo.setBooster(prev.user.id, v)
      sus = prev.set(_.copy(booster = v))
      _ <- reportApi.process(mod, sus, Set(Room.Other))
      _ <- logApi.booster(mod, sus, v)
    } yield {
      if (v) {
        lilaBus.publish(lila.hub.actorApi.mod.MarkBooster(sus.user.id), 'adjustBooster)
        notifier.reporters(mod, sus)
      }
    }
  }

  def autoBooster(winnerId: User.ID, loserId: User.ID): Funit =
    logApi.wasUnbooster(loserId) map {
      case false => reporter ! lila.hub.actorApi.report.Booster(winnerId, loserId)
      case true =>
    }

  def setTroll(mod: Mod, prev: Suspect, value: Boolean): Funit = {
    val changed = value != prev.user.troll
    val sus = prev.set(_.copy(troll = value))
    changed ?? {
      UserRepo.updateTroll(sus.user).void >>-
        logApi.troll(mod, sus)
    } >>
      reportApi.process(mod, sus, Set(Room.Coms)) >>- {
        if (value) notifier.reporters(mod, sus)
      }
  }

  def setBan(mod: Mod, prev: Suspect, value: Boolean): Funit = for {
    spy <- userSpy(prev.user.id)
    sus = prev.set(_.copy(ipBan = value))
    _ <- UserRepo.setIpBan(sus.user.id, sus.user.ipBan)
    _ <- logApi.ban(mod, sus)
    _ <- if (sus.user.ipBan) spy.ipStrings.map(firewall.blockIp).sequenceFu >> SecurityStore.disconnect(sus.user.id)
    else firewall unblockIps spy.ipStrings
  } yield ()

  def closeAccount(mod: String, username: String): Fu[Option[User]] = withUser(username) { user =>
    user.enabled ?? {
      logApi.closeAccount(mod, user.id) inject user.some
    }
  }

  def reopenAccount(mod: String, username: String): Funit = withUser(username) { user =>
    !user.enabled ?? {
      (UserRepo enable user.id) >> logApi.reopenAccount(mod, user.id)
    }
  }

  def setTitle(mod: String, username: String, title: Option[String]): Funit = withUser(username) { user =>
    UserRepo.setTitle(user.id, title) >>
      logApi.setTitle(mod, user.id, title) >>-
      lightUserApi.invalidate(user.id)
  }

  def setEmail(mod: String, username: String, email: EmailAddress): Funit = withUser(username) { user =>
    UserRepo.email(user.id, email) >>
      UserRepo.setEmailConfirmed(user.id) >>
      logApi.setEmail(mod, user.id)
  }

  def setPermissions(mod: String, username: String, permissions: List[Permission]): Funit = withUser(username) { user =>
    UserRepo.setRoles(user.id, permissions.map(_.name)) >>
      logApi.setPermissions(mod, user.id, permissions)
  }

  def ipban(mod: String, ip: String): Funit =
    (firewall blockIp IpAddress(ip)) >> logApi.ipban(mod, ip)

  def kickFromRankings(mod: String, username: String): Funit = withUser(username) { user =>
    lilaBus.publish(lila.hub.actorApi.mod.KickFromRankings(user.id), 'kickFromRankings)
    logApi.kickFromRankings(mod, user.id)
  }

  def setReportban(mod: Mod, sus: Suspect, v: Boolean): Funit = (sus.user.reportban != v) ?? {
    UserRepo.setReportban(sus.user.id, v) >>- logApi.reportban(mod, sus, v)
  }

  private def withUser[A](username: String)(op: User => Fu[A]): Fu[A] =
    UserRepo named username flatten "[mod] missing user " + username flatMap op
}
