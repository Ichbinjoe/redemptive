package tech.rayline.core.jsonchat;

import org.bukkit.ChatColor;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
public final class XmlJsonChatConverter {
    private final static DocumentBuilderFactory FACTORY = DocumentBuilderFactory.newInstance();
    private final static String[] BOOLEAN_OPTIONS = new String[]{"bold", "italic", "underlined", "strikethrough", "obfuscated"};
    private final static String[] COLORS = new String[]{"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    private final static String[] CLICK_ACTIONS = new String[]{"open_url", "open_file", "run_command", "suggest_command"};

    public static String parseXML(InputStream xml) throws Exception {
        return parseXML(xml, new HashMap<String, String>());
    }

    public static String parseXML(InputStream xml, Map<String, String> variables) throws Exception {
        DocumentBuilder documentBuilder = FACTORY.newDocumentBuilder();
        Document parse = documentBuilder.parse(xml);
        Element root = parse.getDocumentElement();

        if (!root.getTagName().equals("message"))
            throw new JsonChatParseException("The root node is not a message! Start your xml with <message>!");
        List<Element> t = getChildrenElementsOfName(root, "t");
        if (t.size() != 0) {
            return parseContent(t, variables).toString();
        }
        throw new JsonChatParseException("You did not specify a message!");
    }

    private static JSONObject parseContent(List<Element> elements, Map<String, String> variables) throws JsonChatParseException {
        JSONObject rootObject = new JSONObject();
        rootObject.put("text", "");
        rootObject.put("color", "white");
        for (Element item : elements) {
            JSONObject nodeObject = parseContent(item, variables);
            Object extraRaw = rootObject.get("extra");
            if (extraRaw == null) {
                extraRaw = new JSONArray();
                rootObject.put("extra", extraRaw);
            }
            ((JSONArray) extraRaw).add(nodeObject);
        }
        return rootObject;
    }

    private static List<Element> getChildrenElementsOfName(Element parent, String name) {
        List<Element> elements = new ArrayList<>();
        NodeList childNodes = parent.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node item = childNodes.item(i);
            if (item.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) item;
            if (!elem.getTagName().equals(name)) continue;
            elements.add(elem);
        }
        return elements;
    }

    private static String formatContent(String content, Map<String, String> variables) {
        content = ChatColor.translateAlternateColorCodes('&', content);
        /*
        Instead of iterating through each key like the last implementation, this
        implementation searches for the key indicators, and replaces them, pulling
        the value from the map instead of using each of the variables and searching
        through. This should reduce the complexity from the original O(n) down to
        a simple O(1) complexity in terms of the number of variables supplied,
        meaning lots of variables can be shoved in without any real performance
        hit.
         */
        StringBuilder stringBuffer = new StringBuilder(content);
        int patternStart = 0;
        // must have space to fit at least 5 characters (4 for {{ }} and 1 for actual key
        while (patternStart < stringBuffer.length() - 4 && (patternStart = stringBuffer.indexOf("{{", patternStart)) != -1) {
            int capKeyStart = stringBuffer.indexOf("}}", patternStart);
            if (capKeyStart == -1)
                break;
            String key = stringBuffer.substring(patternStart + 2, capKeyStart);
            String value = variables.get(key);
            if (value != null)
                stringBuffer.replace(patternStart, capKeyStart + 2, value);
            patternStart = capKeyStart + 2;
        }
        return stringBuffer.toString();
    }

    /**
     * Returns a json object for a specific text node
     *
     * @param textNode The text node
     * @return The json object
     */
    private static JSONObject parseContent(Element textNode, Map<String, String> variables) throws JsonChatParseException {
        JSONObject elementContent = new JSONObject();

        for (String booleanOption : BOOLEAN_OPTIONS) {
            if (!textNode.hasAttribute(booleanOption)) continue;
            elementContent.put(booleanOption, Boolean.valueOf(textNode.getAttribute(booleanOption)));
        }

        String color = null;
        if (textNode.hasAttribute("color"))
            color = textNode.getAttribute("color").toLowerCase();
        if (!contains(COLORS, color))
            color = "white";
        elementContent.put("color", color);

        NodeList childNodes = textNode.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node item = childNodes.item(i);
            if (item.getNodeType() == Node.TEXT_NODE) {
                String content = formatContent(item.getTextContent(), variables);
                if (elementContent.get("extra") == null) {
                    String previousContent = (String) elementContent.get("text");
                    elementContent.put("text", previousContent != null ? previousContent : "" +
                            content);
                } else {
                    JSONObject plainTextExtra = new JSONObject();
                    plainTextExtra.put("text", content);
                    ((JSONArray) elementContent.get("extra")).add(plainTextExtra);
                }
                continue;
            }
            if (item.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) item;
            switch (element.getTagName()) {
                case "hover":
                    elementContent.put("hoverEvent", parseHoverEvent(element, variables));
                    break;
                case "click":
                    elementContent.put("clickEvent", parseClickEvent(element, variables));
                    break;
                case "t":
                    JSONObject nodeObject = parseContent(element, variables);
                    Object extraRaw = elementContent.get("extra");
                    if (extraRaw == null) {
                        extraRaw = new JSONArray();
                        elementContent.put("extra", extraRaw);
                    }
                    ((JSONArray) extraRaw).add(nodeObject);
                    break;
                default:
                    throw new JsonChatParseException("Unknown tag type " + element.getTagName());
            }
        }
        return elementContent;
    }

    private static String getFirstLevelTextContent(Node node) {
        NodeList list = node.getChildNodes();
        StringBuilder textContent = new StringBuilder();
        for (int i = 0; i < list.getLength(); ++i) {
            Node child = list.item(i);
            if (child.getNodeType() == Node.TEXT_NODE)
                textContent.append(child.getTextContent());
        }
        return textContent.toString();
    }

    private static JSONObject parseHoverEvent(Element element, Map<String, String> variables) throws JsonChatParseException {
        JSONObject hoverEvent = new JSONObject();
        String action = null;
        Object value = null;
        List<Element> childTs = getChildrenElementsOfName(element, "t"),
                childItem = getChildrenElementsOfName(element, "item"),
                childAchievement = getChildrenElementsOfName(element, "achievement");

        if (childTs.size() != 0) {
            action = "text";
            value = parseContent(childTs, variables);
        } else if (childItem.size() == 1) {
//            action = "item";
//            Element item = childItem.get(0);
            //todo
            throw new JsonChatParseException("Cannot handle items at the moment, stay tuned!");
        } else if (childAchievement.size() == 1) {
            action = "achievement";
            value = formatContent(getFirstLevelTextContent(childAchievement.get(0)), variables);
        }

        if (value != null) {
            hoverEvent.put("action", "show_" + action);
            hoverEvent.put("value", value);
            return hoverEvent;
        }
        throw new JsonChatParseException("Invalid hover event specified!");
    }

    private static JSONObject parseClickEvent(Element element, Map<String, String> variables) throws JsonChatParseException {
        JSONObject jsonObject = new JSONObject();
        String action;
        if (!element.hasAttribute("action") || !contains(CLICK_ACTIONS, action = element.getAttribute("action")))
            throw new JsonChatParseException("No (or invalid) action specified for click event!");

        jsonObject.put("action", action);
        jsonObject.put("value", formatContent(getFirstLevelTextContent(element), variables));
        return jsonObject;
    }

    //despite this already being in GeneralUtils, I want this class to be self contained for portability
    private static <T> boolean contains(T[] array, T obj) {
        for (T t : array)
            if ((obj == null && t == null) || (obj != null && obj.equals(t)))
                return true;
        return false;
    }

    public static InputStream streamFrom(CharSequence sequence) {
        return new ByteArrayInputStream(sequence.toString().getBytes(StandardCharsets.UTF_8));
    }
}
