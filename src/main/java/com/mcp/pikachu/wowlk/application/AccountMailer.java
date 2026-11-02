package com.mcp.pikachu.wowlk.application;

import com.mcp.pikachu.wowlk.config.WowlkProperties;
import com.mcp.pikachu.wowlk.infra.AbraClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** E-mails simples (texto puro, sem HTML) enviados pela Abra API. Falha de e-mail nunca derruba o cadastro. */
@Slf4j
@Component
public class AccountMailer {

    private final AbraClient abra;
    private final String realmlist;

    public AccountMailer(AbraClient abra, WowlkProperties props) {
        this.abra = abra;
        this.realmlist = props.realmlist();
    }

    public boolean sendActivated(String email, String username, boolean pt) {
        String realm = realmlist == null || realmlist.isBlank() ? "" : (pt
                ? "\nSe nao usar o launcher, coloque no realmlist.wtf: set realmlist " + realmlist + "\n"
                : "\nIf you don't use the launcher, put this in realmlist.wtf: set realmlist " + realmlist + "\n");
        String subject = pt ? "WoW Classic Plus LK - conta ativada" : "WoW Classic Plus LK - account activated";
        String body = pt
                ? "Ola!\n\nSua conta " + username + " foi criada e ja esta ativada.\n"
                  + "Entre pelo launcher (OLK Plus Launcher) com seu usuario e senha e comece sua aventura." + realm
                  + "\nBom jogo!\nEquipe WoW Classic Plus LK"
                : "Hello!\n\nYour account " + username + " has been created and is now active.\n"
                  + "Sign in with the OLK Plus Launcher using your username and password and start your adventure." + realm
                  + "\nHave fun!\nThe WoW Classic Plus LK team";
        return send(email, subject, body, "ativacao");
    }

    public boolean sendFailed(String email, String username, boolean pt) {
        String subject = pt ? "WoW Classic Plus LK - falha no cadastro" : "WoW Classic Plus LK - registration failed";
        String body = pt
                ? "Ola!\n\nNao conseguimos criar a sua conta " + username + " por um problema no nosso servidor. "
                  + "Pedimos desculpas pelo transtorno.\n\nPor favor, tente se cadastrar novamente mais tarde pelo site.\n\n"
                  + "Equipe WoW Classic Plus LK"
                : "Hello!\n\nWe could not create your account " + username + " because of a problem on our server. "
                  + "We are sorry for the inconvenience.\n\nPlease try to register again later on the website.\n\n"
                  + "The WoW Classic Plus LK team";
        return send(email, subject, body, "falha");
    }

    /** Nome ou e-mail ja em uso. Nao diz qual dos dois (evita enumeracao de contas). */
    public boolean sendConflict(String email, String username, boolean pt) {
        String subject = pt ? "WoW Classic Plus LK - cadastro nao concluido" : "WoW Classic Plus LK - registration not completed";
        String body = pt
                ? "Ola!\n\nNao foi possivel criar a conta " + username + ": o nome de usuario ou este e-mail ja estao em uso.\n"
                  + "Se a conta e sua, entre pelo launcher. Senao, cadastre-se de novo com outro nome de usuario.\n\n"
                  + "Equipe WoW Classic Plus LK"
                : "Hello!\n\nWe could not create the account " + username + ": the username or this e-mail address is already in use.\n"
                  + "If the account is yours, sign in with the launcher. Otherwise, register again with a different username.\n\n"
                  + "The WoW Classic Plus LK team";
        return send(email, subject, body, "conflito");
    }

    private boolean send(String email, String subject, String body, String kind) {
        try {
            abra.sendTextEmail(email, subject, body);
            log.info("[wowlk] e-mail de {} enviado para {}", kind, mask(email));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("[wowlk] falha ao enviar e-mail de {} para {}: {}", kind, mask(email), e.getMessage());
            return false;
        }
    }

    /** a***@dominio.com — e-mail completo nao vai pro log. */
    public static String mask(String email) {
        if (email == null) return "-";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
