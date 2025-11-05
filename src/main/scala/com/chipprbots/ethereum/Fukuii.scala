package com.chipprbots.ethereum

import java.util.logging.LogManager

import org.rocksdb

import com.chipprbots.ethereum.console.ConsoleUI
import com.chipprbots.ethereum.nodebuilder.StdNode
import com.chipprbots.ethereum.nodebuilder.TestNode
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Logger

object Fukuii extends Logger {
  def main(args: Array[String]): Unit = {
    LogManager.getLogManager().reset(); // disable java.util.logging, ie. in legacy parts of jupnp

    // Check for --no-tui flag
    val enableConsoleUI = !args.contains("--no-tui")

    // Initialize console UI if enabled
    val consoleUI = if (enableConsoleUI) {
      val ui = ConsoleUI.getInstance()
      ui.initialize()
      if (ui.isEnabled) {
        Some(ui)
      } else {
        None
      }
    } else {
      log.info("Console UI disabled via --no-tui flag")
      None
    }

    // Display Fukuii ASCII art on startup (only if console UI is not enabled)
    if (consoleUI.isEmpty) {
      printBanner()
    }

    val node =
      if (Config.testmode) {
        log.info("Starting Fukuii in test mode")
        deleteRocksDBFiles()
        new TestNode
      } else new StdNode

    log.info("Fukuii app {}", Config.clientVersion)
    log.info("Using network {}", Config.blockchains.network)

    // Update console UI with network info
    consoleUI.foreach { ui =>
      ui.updateNetwork(Config.blockchains.network)
      ui.updateConnectionStatus("Starting node...")
      ui.render()
    }

    // Add shutdown hook to cleanup console UI
    Runtime.getRuntime.addShutdownHook(new Thread(() => consoleUI.foreach(_.shutdown())))

    node.start()
  }

  private def deleteRocksDBFiles(): Unit = {
    log.warn("Deleting previous database {}", Config.Db.RocksDb.path)
    rocksdb.RocksDB.destroyDB(Config.Db.RocksDb.path, new rocksdb.Options())
  }

  private def printBanner(): Unit = {
    val banner = """
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                            ›ízzzzzí›                                                            
                                                         ›zzzzzízzzzzzz›                                                         
                                                      —ízzzízzzzzÏ6zzzzzzz—                                                      
                                                   —zzzzzzzzzzízzÏÅgÅGzzzzzzz—                                                   
                                   6ÆÅgggÆg     {zzzzzzízzzzízzzzzz6Å6ÆÏzzzzzzzí—                                                
                                 ÆÏ ›í———{—{GÅÏzzzzízzzzzzzzzzzíízzzÏÆüÆüzzzízzzzzz{                                             
                               üÅ ————{—{{—{——GgzzzzzzzzzíízzzzzzzzzzügüÅíízzzzzzzzzzí{                                          
                               Æ›{{{—züÞÇüz{í{{6ÅzzzzzzzzzzzzízzzzzzzzgÇGÞzzzzzzzzzzzzzízz                                       
                              6Þ———{zÅüzz6Æüí———ÞÞzzzzzzzzzzízzííízzzz6G6gzzzzízzzzzzzzzzzzzíÇÅÅÇügÅÆÆ{                          
                              ÆííízzüÆzzzzzÅ6{——{Æzzzzzzzzzzzzzíízzzzíüg6gzzzzzzzzz{ízzzízGÅÇ6gÅ6                                
                             Æg ›————ÞÆzzzzGGí{—{Åüzzzzzízzízzzízzízzz6G6gzzzzzzízzzzzzzÆgÏÅÅzzzzzz                              
                         ÏÆG— ————————6ÅzzíGG{———Åüzzzzí{ízzzzzzzzzzzíGÞÞGzzzzzzzzzzzzÅGÏÅÞzzzzízzzzzí                           
                       —Åí›—————{—{{———ÅÇzzgÇ{—{íÆzzzzzíízzzízízzzzízzÆÏÅüzzí{{zzzzzÞÅÏÅÞzzzzzzzzzzízzzzz                        
                     zÏÅ —6Ç————{—Åz{——6gzÏÆí———G6zzízzízzzzzzzÏ6ÇÞÞÇGg6gzzzzzzzízÏÆüÞÅzzzzzzzízzzzzzzízzzzz                     
                 ›zízzÅ6›{ÆÆ—{———{ÇÆ{——gÞíÅü———ÏÅzzzzzzzzzGÅÆGÏg—››››——íGÆÆÅGzzzzÇÅüÅÏzízzízzzzzzízzízzízzzzzzz—                 
                zzzzzzÆ6—————{—{—z————{ÆÏÏÆÏíí{g6zzzzzÏgÆÇ——{ÞÏ{í{í{{íÏí{{—›—6ÅÅÅÞÞgzzzzzzzzzzzízzzzzzzzzzzzzzzzz                
                zzzzzzüÅ—{——{ÆÆÅGÆí——{ÅÏzGÞí———gÇzízÏÅÅ{—ízzgí{íííí{› ›{ííííí{zÆüÆÆ6zzzzzzzzízí{{zízzzzzzzízzzzzz                
                zzzzzzíüÆÏ———{ÅÇÅ{{—GÆzízÞGÏ——{{ÅÇígÅ{{zzzÏGííí{íí{—  {í{ííííÇÅüÆüíGÆ6zízzzzzzzíízzzzzzzzzzzzzzzz                
                zzzzízzzz6ÅÅÇÏÏÏ6GÆGzízzzzÆüü{—{—ÏgÆÆÅÇzzzígíííí{{{{  ››››{íüÞÆGüz{ííÆgzzzzzzzzzzzzzzzízízzzzzzzz                
                zzzzzzzzzzzzzzzzzzzzzzzízzüÆüÏ{—í{——{zg6zzzzÞíí{   — ›—›—{{íízzí{{í{{íÆGzzízízzzzzzzzzzzzzzzízzzz                
                zzzzzzzzzzzzzzzzízzzzzzzzzzzGÅ6ÏÏzíííüÆÏzzzGíí{{—›— ›{ííííí{í{íííííí{{íÆÏzzzzzzzzzzzzízzzzzzzzzzz                
                zízzzzzzzzzízzízzzzzzzzzzzzízzüÅÆGÇ6ggÏzzzÏ6í{   › ›{ííí{{ííÞG6ÏzÏÇÅÞí{ÆÅzzzzízzzzzzzzzízzzzzzzzz                
                zzzzíííí{zzzzzzzzzzzíízí{ízzzííÅ6zzzÏGÆgÏzÇíí{í{{› {{{{{ííÏíííí{íí{í{gÞ6Åzzzzzz{{zzzzzzzzzzzzzzzz                
                zzzzzzzzzzízzzzzzzzzzzzzízzzízGÅízÏÅÆÆÆÆÆÅÏíí{í{—    ›{ííííÏGgÅgÇ{íí{íÞÅÅzzzzízzzzzzzzzzzzzzzzzzz                
                zzzzzzzzzzzzzízzí{zzzzzzzzzzzzÆízÏÆÆÆÆ6  üÆÞ{ííí{ ›{{íííííGÆgÅÆÆÆÆÆG{í{íÆgzzzzzzzzzzzzzízzízzzzzzz                
                zízzízzzzzzzzzzízízzzzízzíízz6Æ{zgÆÆÆ{  gÆg{í{{{›{ííí{íÏÆÞ   6ÆÆÆÅgGí{{ÅGzíízzzzzzzízzzzíííízzzzz                
                zzzzzzzzízzzzízzzzzzízzzzízzzÇÆ—zÆÆÆÆÆÆÆÆÆÞí{íí{íííííízÆÆ6   ÆÆÆÆÅGg{í{ÆGzzzzízzzzzzzzzzzzízzzzzz                
                zzzzzzízzzzzzzííüÇÞ6zzzzzzzzz6Æ{zÅÅÆÆÆÆÆÆÆÏíí{íííí{{{íÇÆÆÆÆÆÆÆÆÆÆGGg{{íÆüízzzzízzzzzzzízzzzzzzzíz                
                zzzízzzzízzzzgÆÆÆz››gÆüzzzzzzzÅzzÇÆgÆÆÆÞÆÇ{{{{ííí{íí{íÞÆÆÆÆÆÆÆÆÆgÞÅüí{ÞÅzzzzzííízzízzzzzzízzízzzz                
                zízzzízzzíÏÅÅí›{ííÅí{›ÅÅzzízzz6ÆÏz6ÆÅgÅÆÏííííí{íííí{í{6ÆÆÆÆÆÆGÅGÞÅÞííÏÆzzzzízzzzzzzzzzzzzzzzzzzzz                
                zzzzzzzízÅÅ››í{{íí6Ïí{—GÆzzzzzzÇÆ6zzzGííííííí{íí{í{ííí{GÅgÅÅgÞÞGÅü{í6Æüzzzzzzzzzzzzzízzzzzzzzzízz                
                zzzzzzíÏÆz›íí{í{{íÏ6ííí{GÆzízzzzzÅÆ6ÞÏííí{Þgü{{zÇÅÅÞíí{íígÆÆÆÆGí{{ÏgÅzzzzzzzzzzzízízzzzízzzzzzízz                
                zzzzzz6Å—›{íí{íí{íÅGíííííggzzízzzzzüÅÞíííízÅGÅG666ÇGííííííí{{íízüÞÆÇzzzzzzzzÞgGzzzzzzízíízzzzzzzz                
                zzzzzüÅ››ííí{íí{íÅÅüí{í{í{Å6zzzízzzzzÅgí{í{ÇgÏÏÏÏÏÅí{íí{zÏÏüüüÇÅÅÇzzzzzzzzÅÅ{zÆzízzzzzz{{zzzzzzzz                
                zzzzzÆz›íí{{{íííÅÆgüz{íííííÆÏzzízzzzzzÞÆÇííízÅÆÆÅüí{{ízüüGÅÆÆÅüzzzzzzzzzÅÆ—zügÅzzzzzízízzzzzzzzzz                
                zzízGg›{{ííí{íÅgzÇÆüüíí{{{{6Æzzzí{ízzzzzÇÆÅÞüí{íízzÏüÇgÆÅÅgzzzzzzzzzzzÇÅííüüüÅzzzzíízzzzzzzzzzzzz                
                zzzzÆz—{í{{íÏüÆ6íÞÆüüí{íííí{gGzzzzzzzzí6ÆÅÆÅÞgÆÅÅÅÅÅgGGGGÆgÞÞÅÅzzzzzzÆG{üüüüGÆzzzzízzzzzzízzzzzzz                
                zzzÏÅ›í{íí{6GÅÅGÏÆüüüüí{{{íí{ÅÏzzzzzízgGÇggÏüüÆÆÅgGggÅÆÅGüü6ÇÇGgzzzÏÆzzüüüüüÆzzzzzzzzííízízzzzzzz                
                zzzÏÅ›íííüÆüÏzzízzÅÅüüÏíííííí6ÆzzzzÇÅÅÆÇÇÆíííÏÞgÞGGÞGÅGüüÏÇÆGGÆgzzÇÅ{Ïüüüü6ÅgzzzzzzzGÆüÅÏzzzzzzzz                
                zzzÏÆ›{{zÅzzízzzízÆÅ6üüz{íííííg6GÆgzüüÆÞgÞíí{ííGzüüíííí{{ÏÅ6Ïz6ÆzÞÅ{üüüüÇÇÞÅzzízíÏÅg—›{Åüzzzzzzíz                
                zzzzÆGííÆÞzzzízzzíÅgüüüüÏ{{í{í6ÆÏ{{ízüügÆü{í{{Þ{í{íí{ííííÆÇ66ÅÆÆgÅzüüüÇÇÇÇÆzzzzÅÆ6Åg{{íÅzzízzzzzz                
                zzzzzÆzzÅízízzzízzzzÞÆGüüüzíí{6ÇÅzÏüüGÆÞÅüííízÇ{ííí{ííí{ÏÆGÞg{ízÆ6üü6ÇÇÇÇÆüügÆ6››—{íÞÞÏÆzzzz{ízzz                
                zízzzÏÆÇÅzzízzzzízzzzíGÆÇüüüü6ÅüÆ6ÅÆGzzzÞgíí{Çz{ííííí{{zÅÇgÆÅÏ{ízÆÇ6ÇÇÇÞÆÆgí››ÏÆííí{{6ÆGízzzzzzzz                
                zzzízízgÆzzzízzzzzzzzzzzÏÅÆÅÅggÆÅüzzzzzzzÇÅ6zGz{ííí{í{ÞGÇÇÞÅggÏíízÆÅÆÆÇ—››—{í{ííÞÞíííüÆzzzzzzzzzz                
                zzzzzzzzzzzzzzí{ízzzízzzzzzzzízzzzzzzízzzzüÆÇÇÇGÅgÞGÅGÇÇÇGÅÇÇgÅüÏízÆz{ízÇzííí{íí{6ÞÏüÅÇzzzzzzzzzz                
                  ízzzzízzzzzzzzzzíízzzzzzzzzzzÏÅÅÆgzzzízzzggÇÇÇÇÇÇÅÇÇÇÇÆÅGÇÇÇÞÆÅGgÅÅ{{íííÇí{{ííí{ÅüÅÅzzzzzzzzí                  
                     zzzzzzzzzzígÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÅÅÆÆÆÆÆÆÆÆÆÆÆÆgÆÆÆÆÆÆÆÆÆÆÆÆÅÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆüüGÅÅzzzzzzí                     
                        {zzízzzzÆÅ         Æ—   gÆÆÆ   zÆÇ   ÇÆÆü    Æg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆüÅgzzzz{                        
                           ízzízÆÅ        ›Æ—   gÆÆÆ   zÆÇ   ÇÆz   ›ÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆÆÞzzí                           
                              ízÆÅ   GÆÆÆÆÆÆ—   gÆÆÆ   zÆÇ   Ç{   {ÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆzí                              
                                ÆÅ   —íí{ÏÆÆ—   gÆÆÆ   zÆÇ       zÆÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   ÏÆÆ                                
                                ÆÅ        ÆÆ—   gÆÆÆ   zÆÇ       6ÆÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÅ   GÅÅÅÅÆÆ—   gÆÆÆ   zÆÇ   6    ÏÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÅ   GÆÅÏÏGÆÏ   zÆÆz   6ÆÇ   ÇÆ    íÆÆÆ    GÆg    ÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÅ   GÆg   ÆÆ         —ÆÆÇ   ÇÆÆ›   ›ÆÆÇ         GÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÆGGGÅÆÏ    ÅÆÆGÇÏÏÇGÆÆÆÆÆÞÞGÅÆÆÆGGGGÅÆÆÆgÞüÏüÞÅÆÆÆÆÅGGGÆÆÆGGGÆÆÅ                                
                                 í666ü        zÇGggggÞzzzÞGGGGÏzüGÆGGGÞÇüGgÅÅgG6—   z666Ï —ü66ü›                                 
                                                      —zzzzzzzzzzzÆíí6gzzz—                                                      
                                                          zzzzzzzzgÇÏÆÏ›                                                         
                                                             ízzzzzÅÅÞ                                                           
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 """

    println(banner)
  }
}
