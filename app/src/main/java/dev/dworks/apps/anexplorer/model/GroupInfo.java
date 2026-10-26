package dev.dworks.apps.anexplorer.model;

import java.util.Collections;
import java.util.List;

import dev.dworks.apps.anexplorer.fragment.RootsFragment.*;

/**
 * Created by HaKr on 07/08/16.
 */

public class GroupInfo {
    public String label;
    public List<Item> itemList;

    /** Shown as this one row instead of a header, e.g. Home at the top of the sidebar. */
    public Item item;

    public GroupInfo(String text, List<Item> list){
        label = text;
        itemList = list;
    }

    public GroupInfo(Item item){
        this.item = item;
        itemList = Collections.emptyList();
    }
}
