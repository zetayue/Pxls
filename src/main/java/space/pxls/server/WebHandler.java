package space.pxls.server;

import com.mashape.unirest.http.exceptions.UnirestException;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;
import space.pxls.App;
import space.pxls.auth.AuthService;
import space.pxls.auth.DiscordAuthService;
import space.pxls.auth.GoogleAuthService;
import space.pxls.auth.RedditAuthService;
import space.pxls.data.DBPixelPlacement;
import space.pxls.user.Role;
import space.pxls.user.User;
import space.pxls.util.AuthReader;
import space.pxls.util.IPReader;

import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebHandler {
    private Map<String, AuthService> services = new ConcurrentHashMap<>();

    {
        services.put("reddit", new RedditAuthService("reddit"));
        services.put("google", new GoogleAuthService("google"));
        services.put("discord", new DiscordAuthService("discord"));
    }

    public void ban(HttpServerExchange exchange) {
        User user = parseUserFromForm(exchange);
        if (user != null) {
            FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
            FormData.FormValue reason = data.getFirst("reason");
            String reason_str = "";
            if (reason != null) {
                reason_str = reason.getValue();
            }
            App.getUserManager().banUser(user, 86400, reason_str);
            exchange.setStatusCode(200);
        } else {
            exchange.setStatusCode(400);
        }
    }

    public void unban(HttpServerExchange exchange) {
        User user = parseUserFromForm(exchange);
        if (user != null) {
            App.getUserManager().banUser(user, 0);
            exchange.setStatusCode(200);
        } else {
            exchange.setStatusCode(400);
        }
    }

    public void check(HttpServerExchange exchange) {
        User user = parseUserFromForm(exchange);
        if (user != null) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send(App.getGson().toJson(new Packet.UserInfo(user.getName(), user.getRole().name(), user.isBanned(), user.getBanReason(), user.getBanExpiryTime())));
        } else {
            exchange.setStatusCode(400);
        }
    }

    public void signUp(HttpServerExchange exchange) {
        String ip = exchange.getAttachment(IPReader.IP);
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);

        FormData.FormValue nameVal = data.getFirst("name");
        FormData.FormValue tokenVal = data.getFirst("token");
        if (nameVal == null || tokenVal == null) {
            exchange.setStatusCode(StatusCodes.SEE_OTHER);
            exchange.getResponseHeaders().put(Headers.LOCATION, "/");
            exchange.getResponseSender().send("");
            return;
        }

        String name = nameVal.getValue();
        String token = tokenVal.getValue();
        if (token.isEmpty()) {
            exchange.setStatusCode(StatusCodes.SEE_OTHER);
            exchange.getResponseHeaders().put(Headers.LOCATION, "/");
            exchange.getResponseSender().send("");
            return;
        } else if (name.isEmpty()) {
            exchange.setStatusCode(StatusCodes.SEE_OTHER);
            exchange.getResponseHeaders().put(Headers.LOCATION, "/signup.html?token=" + token + "&error=Username%20cannot%20be%20empty.");
            exchange.getResponseSender().send("");
            return;
        } else if (!name.matches("[a-zA-Z0-9_\\-]+")) {
            exchange.setStatusCode(StatusCodes.SEE_OTHER);
            exchange.getResponseHeaders().put(Headers.LOCATION, "/signup.html?token=" + token + "&error=Name%20contains%20invalid%20characters.");
            exchange.getResponseSender().send("");
            return;
        } else if (!App.getUserManager().isValidSignupToken(token)) {
            exchange.setStatusCode(StatusCodes.SEE_OTHER);
            exchange.getResponseHeaders().put(Headers.LOCATION, "/signup.html?token=" + token + "&error=Invalid%20signup%20token.");
            exchange.getResponseSender().send("");
            return;
        }

        User user = App.getUserManager().signUp(name, token, ip);

        if (user == null) {
            exchange.setStatusCode(StatusCodes.SEE_OTHER);
            exchange.getResponseHeaders().put(Headers.LOCATION, "/signup.html?token=" + token + "&error=Username%20is%20taken,%20try%20another%3F");
            exchange.getResponseSender().send("");
            return;
        }

        String loginToken = App.getUserManager().logIn(user, ip);
        exchange.setStatusCode(StatusCodes.SEE_OTHER);
        exchange.getResponseHeaders().put(Headers.LOCATION, "/");
        exchange.setResponseCookie(new CookieImpl("pxls-token", loginToken).setPath("/"));
        exchange.getResponseSender().send("");
    }

    public void auth(HttpServerExchange exchange) throws UnirestException {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this::auth);
            return;
        }

        String id = exchange.getRelativePath().substring(1);
        String ip = exchange.getAttachment(IPReader.IP);

        AuthService service = services.get(id);
        if (service != null) {
            Deque<String> code = exchange.getQueryParameters().get("code");
            if (code == null) {
                exchange.setStatusCode(StatusCodes.SEE_OTHER);
                exchange.getResponseHeaders().put(Headers.LOCATION, "/");
                exchange.getResponseSender().send("");

                return;
            }
            String token = service.getToken(code.element());
            String identifier;
            try {
                identifier = service.getIdentifier(token);
            } catch (AuthService.InvalidAccountException e) {
                exchange.setStatusCode(StatusCodes.SEE_OTHER);
                exchange.getResponseHeaders().put(Headers.LOCATION, "/error.html?error=" + e.getMessage());
                exchange.getResponseSender().send("");
                return;
            }

            if (token != null && identifier != null) {
                String login = id + ":" + identifier;
                User user = App.getUserManager().getByLogin(login);
                if (user == null) {
                    String signUpToken = App.getUserManager().generateUserCreationToken(login);

                    exchange.setStatusCode(StatusCodes.SEE_OTHER);
                    exchange.setResponseCookie(new CookieImpl("pxls-signup-token", signUpToken).setPath("/"));
                    exchange.getResponseHeaders().put(Headers.LOCATION, "/signup.html?token=" + signUpToken);
                    exchange.getResponseSender().send("");
                } else {
                    String loginToken = App.getUserManager().logIn(user, ip);
                    exchange.setStatusCode(StatusCodes.SEE_OTHER);
                    exchange.getResponseHeaders().put(Headers.LOCATION, "/");
                    exchange.setResponseCookie(new CookieImpl("pxls-token", loginToken).setPath("/"));
                    exchange.getResponseSender().send("");
                }
            } else {
                exchange.setStatusCode(StatusCodes.SEE_OTHER);
                exchange.getResponseHeaders().put(Headers.LOCATION, "/");
                exchange.getResponseSender().send("");
            }
        }
    }

    public void signIn(HttpServerExchange exchange) {
        String id = exchange.getRelativePath().substring(1);

        AuthService service = services.get(id);
        if (service != null) {
            exchange.setStatusCode(StatusCodes.SEE_OTHER);
            exchange.getResponseHeaders().put(Headers.LOCATION, service.getRedirectUrl());
            exchange.getResponseSender().send("");
        }
    }

    public void info(HttpServerExchange exchange) {
        exchange.getResponseHeaders().add(HttpString.tryFromString("Content-Type"), "application/json");
        exchange.getResponseSender().send(App.getGson().toJson(
                new Packet.HttpInfo(App.getWidth(), App.getHeight(), App.getConfig().getStringList("board.palette"), App.getConfig().getString("captcha.key"))));
    }

    public void data(HttpServerExchange exchange) {
        exchange.getResponseSender().send(ByteBuffer.wrap(App.getBoardData()));
    }

    public void logout(HttpServerExchange exchange) {
        Cookie tokenCookie = exchange.getRequestCookies().get("pxls-token");

        if (tokenCookie != null) {
            App.getUserManager().logOut(tokenCookie.getValue());
        }

        exchange.setStatusCode(StatusCodes.SEE_OTHER);
        exchange.getResponseHeaders().put(Headers.LOCATION, "/");
        exchange.getResponseSender().send("");
    }

    public void lookup(HttpServerExchange exchange) {
        User user = exchange.getAttachment(AuthReader.USER);
        if (user == null || user.getRole().lessThan(Role.MODERATOR)) {
            exchange.setStatusCode(StatusCodes.FORBIDDEN);
            exchange.endExchange();
            return;
        }

        Deque<String> xq = exchange.getQueryParameters().get("x");
        Deque<String> yq = exchange.getQueryParameters().get("y");

        if (xq.isEmpty() || yq.isEmpty()) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }

        int x = Integer.parseInt(xq.element());
        int y = Integer.parseInt(yq.element());
        if (x < 0 || x >= App.getWidth() || y < 0 || y >= App.getHeight()) {
            exchange.setStatusCode(StatusCodes.BAD_REQUEST);
            exchange.endExchange();
            return;
        }

        DBPixelPlacement pp = App.getDatabase().getPixelAt(x, y);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(App.getGson().toJson(pp));
    }

    private User parseUserFromForm(HttpServerExchange exchange) {
        FormData data = exchange.getAttachment(FormDataParser.FORM_DATA);
        if (data != null) {
            FormData.FormValue username = data.getFirst("username");
            if (username != null) {
                return App.getUserManager().getByName(username.getValue());
            }
        }
        return null;
    }
}
