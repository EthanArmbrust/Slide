package me.ccrama.redditslide.Adapters;

import android.content.Context;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import me.ccrama.redditslide.Activities.SetupWidget;
import me.ccrama.redditslide.R;
import me.ccrama.redditslide.SantitizeField;
import me.ccrama.redditslide.Visuals.Palette;
import me.ccrama.redditslide.Widget.SubredditWidgetProvider;


/**
 * Created by ccrama on 8/17/2015.
 */
public class SubChooseAdapter extends ArrayAdapter<String> {
    private final List<String> objects;
    private Filter filter;
    public ArrayList<String> baseItems;
    public ArrayList<String> fitems;
    public boolean openInSubView = true;

    public SubChooseAdapter(Context context, ArrayList<String> objects, ArrayList<String> allSubreddits) {
        super(context, 0, objects);
        this.objects = new ArrayList<>(allSubreddits);
        filter = new SubFilter();
        fitems = new ArrayList<>(objects);
        baseItems = new ArrayList<>(objects);
    }

    @Override
    public boolean isEnabled(int position) {
        return false;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public Filter getFilter() {

        if (filter == null) {
            filter = new SubFilter();
        }
        return filter;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        convertView = LayoutInflater.from(getContext()).inflate(R.layout.subforsublist, parent, false);

        final TextView t =
                ((TextView) convertView.findViewById(R.id.name));
        t.setText(fitems.get(position));

        final String subreddit = (fitems.get(position).contains("+") || fitems.get(position).contains("/m/")) ? fitems.get(position) : SantitizeField.sanitizeString(fitems.get(position).replace(getContext().getString(R.string.search_goto) + " ", ""));

        convertView.findViewById(R.id.color).setBackgroundResource(R.drawable.circle);
        convertView.findViewById(R.id.color).getBackground().setColorFilter(Palette.getColor(subreddit), PorterDuff.Mode.MULTIPLY);

        if(getContext() instanceof SetupWidget){
            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ((SetupWidget)getContext()).name = subreddit;
                    SubredditWidgetProvider.lastDone = subreddit;
                    ((SetupWidget)getContext()).startWidget();
                }
            });

        }
        return convertView;
    }

    @Override
    public int getCount() {
        return fitems.size();
    }

    private class SubFilter extends Filter {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            FilterResults results = new FilterResults();
            String prefix = constraint.toString().toLowerCase();

            if (prefix == null || prefix.length() == 0) {
                ArrayList<String> list = new ArrayList<>(baseItems);
                results.values = list;
                results.count = list.size();
            } else {
                openInSubView = true;
                final ArrayList<String> list = new ArrayList<>(objects);
                final ArrayList<String> nlist = new ArrayList<>();

                for (String sub : list) {
                    if (sub.contains(prefix))
                        nlist.add(sub);
                    if (sub.equals(prefix))
                        openInSubView = false;
                }
                if (openInSubView) {
                    nlist.add(prefix);
                }

                results.values = nlist;
                results.count = nlist.size();
            }
            return results;
        }


        @SuppressWarnings("unchecked")
        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            fitems = (ArrayList<String>) results.values;
            clear();
            if (fitems != null) {
                addAll(fitems);
                notifyDataSetChanged();
            }
        }
    }
}