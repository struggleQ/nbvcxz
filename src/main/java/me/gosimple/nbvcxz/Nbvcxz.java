package me.gosimple.nbvcxz;

import me.gosimple.nbvcxz.matching.PasswordMatcher;
import me.gosimple.nbvcxz.matching.match.BruteForceMatch;
import me.gosimple.nbvcxz.matching.match.Match;
import me.gosimple.nbvcxz.resources.Configuration;
import me.gosimple.nbvcxz.resources.ConfigurationBuilder;
import me.gosimple.nbvcxz.resources.Feedback;
import me.gosimple.nbvcxz.resources.FeedbackUtil;
import me.gosimple.nbvcxz.scoring.Result;
import me.gosimple.nbvcxz.scoring.TimeEstimate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This class allows you to do estimates on passwords.  It can be instantiated and configured once, and the same
 * instance should be used for subsequent password estimates.
 *
 * @author Adam Brusselback
 */
public class Nbvcxz
{
    private static StartIndexComparator comparator = new StartIndexComparator();
    private Configuration configuration;

    /**
     * Creates new instance with a default configuration.
     */
    public Nbvcxz()
    {
        this.configuration = new ConfigurationBuilder().createConfiguration();
    }

    /**
     * Creates a new instance with a custom configuration.
     *
     * @param configuration a {@code Configuration} to be used in all estimates.
     */
    public Nbvcxz(Configuration configuration)
    {
        this.configuration = configuration;
    }

    /**
     * Calculates the minimum entropy for a given password and returns that as a Result.
     * <br><br>
     * This method attempts to find the minimum entropy at each position of the password, and then does
     * a backwards pass to remove overlapping matches.  The end result is a list of matches that when
     * their tokens are added up, should equal the original password.
     * <br><br>
     * The result object is guaranteed to match the original password, or throw an exception if it doesn't.
     *
     * @param configuration the configuration file used to estimate entropy.
     * @param password      the password you are guessing entropy for.
     * @return the {@code Result} of this estimate.
     */
    private static Result guessEntropy(final Configuration configuration, final String password)
    {
        Result final_result = new Result(configuration, password, getBestCombination(configuration, password));

        return final_result;
    }

    /**
     * Returns the best combination of matches based on multiple methods.  We run the password through the
     * {@code findGoodEnoughCombination} method test to see if is considered "random".  If it isn't, we
     * run it through the {@code findBestCombination} method, which is much more expensive for large
     * passwords.
     *
     * @param configuration the configuration
     * @param password      the password
     * @return the best list of matches, sorted by start index.
     */
    private static List<Match> getBestCombination(final Configuration configuration, final String password)
    {
        final List<Match> all_matches = getAllMatches(configuration, password);
        final Map<Integer, Match> brute_force_matches = new HashMap<>();
        for (int i = 0; i < password.length(); i++)
        {
            brute_force_matches.put(i, createBruteForceMatch(password, configuration, i));
        }
        if (all_matches == null || all_matches.size() == 0 || isRandom(password, findGoodEnoughCombination(password, all_matches, brute_force_matches)))
        {
            List<Match> matches = new ArrayList<>();
            backfillBruteForce(password, brute_force_matches, matches);
            matches.sort(comparator);
            return matches;
        }
        Collections.sort(all_matches, comparator);
        return findBestCombination(password, all_matches, brute_force_matches);
    }

    /**
     * This is the original algorithm for finding the best matches.  It was much faster, but had the possibility of returning
     * non-optimal lists of matches.  I kept it around to run preliminarily to pass the results to {@code isRandom} so we can
     * see if the password is random and short circuit the more expensive calculations
     *
     * @param password            the password
     * @param all_matches         all matches which have been found for this password
     * @param brute_force_matches map of index and brute force match to fit that index
     * @return a list of matches which is good enough for most uses
     */
    private static List<Match> findGoodEnoughCombination(final String password, final List<Match> all_matches, final Map<Integer, Match> brute_force_matches)
    {
        int length = password.length();
        Match[] match_at_index = new Match[length];
        List<Match> match_list = new ArrayList<>();

        // First pass through the password forward.
        // Set the match to be the lowest average entropy for the length the part of the password takes.
        for (int k = 0; k < length; k++)
        {
            for (Match match : all_matches)
            {
                if (match.getEndIndex() == k)
                {
                    if (match_at_index[k] == null || match_at_index[k].calculateEntropy() / match_at_index[k].getLength() > match.calculateEntropy() / match.getLength())
                    {
                        match_at_index[k] = match;
                    }
                }
            }
        }


        // Now go backwards through the password.
        // Fill in any empty matches with brute force matches, add all matches to the match_list in reverse order.
        int k = length - 1;
        while (k >= 0)
        {
            Match match = match_at_index[k];
            if (match == null)
            {
                match_list.add(brute_force_matches.get(k));
                k--;
                continue;
            }
            match_list.add(match);
            k = match.getStartIndex() - 1;
        }

        // Reverse the order of the list so it's now first to last.
        Collections.reverse(match_list);
        return match_list;
    }

    /**
     * Finds the most optimal matches by recursively building out every combination possible and returning the best.
     *
     * @param password            the password
     * @param all_matches         all matches which have been found for this password
     * @param brute_force_matches map of index and brute force match to fit that index
     * @return the best possible combination of matches for this password
     */
    private static List<Match> findBestCombination(final String password, final List<Match> all_matches, final Map<Integer, Match> brute_force_matches)
    {
        final Map<Match, List<Match>> non_intersecting_matches = new HashMap<>();

        for (int i = 0; i < all_matches.size(); i++)
        {
            Match match = all_matches.get(i);
            List<Match> forward_non_intersecting_matches = new ArrayList<>();

            for (int n = i + 1; n < all_matches.size(); n++)
            {
                Match next_match = all_matches.get(n);
                if (next_match.getStartIndex() > match.getEndIndex() && !(next_match.getStartIndex() < match.getEndIndex() && match.getStartIndex() < next_match.getEndIndex()))
                {
                    boolean to_add = true;
                    for (Match non_intersecting_match : forward_non_intersecting_matches)
                    {
                        if (next_match.getStartIndex() > non_intersecting_match.getEndIndex())
                        {
                            to_add = false;
                            break;
                        }
                    }
                    if (to_add)
                    {
                        forward_non_intersecting_matches.add(next_match);
                    }
                }
            }
            forward_non_intersecting_matches.sort(comparator);
            non_intersecting_matches.put(match, forward_non_intersecting_matches);
        }

        List<Match> seed_matches = new ArrayList<>();
        for (Match match : all_matches)
        {
            boolean seed = true;
            for (List<Match> match_list : non_intersecting_matches.values())
            {
                for (Match m : match_list)
                {
                    if (m.equals(match))
                    {
                        seed = false;
                    }
                }
            }
            if (seed)
            {
                seed_matches.add(match);
            }
        }
        seed_matches.sort(comparator);

        final List<Match> lowest_entropy_matches = new ArrayList<>();

        for (Match match : seed_matches)
        {
            generateMatches(password, match, non_intersecting_matches, brute_force_matches, new ArrayList<>(), lowest_entropy_matches);
        }

        lowest_entropy_matches.sort(comparator);

        return lowest_entropy_matches;
    }

    /**
     * Recursive function to generate match combinations to get an optimal match.
     *
     * @param password                 the password
     * @param match                    a match to start with (or the next match in line)
     * @param non_intersecting_matches map of all non-intersecting matches
     * @param brute_force_matches      map of index and brute force match to fit that index
     * @param matches                  the list of matches being built
     * @param lowest_entropy_matches   the lowest entropy match will be set to this variable
     */
    private static void generateMatches(final String password, final Match match, final Map<Match, List<Match>> non_intersecting_matches, final Map<Integer, Match> brute_force_matches, final List<Match> matches, List<Match> lowest_entropy_matches)
    {
        matches.add(match);

        boolean found_next = false;
        for (Match next_match : non_intersecting_matches.get(match))
        {
            boolean intersects = false;
            for (Match existing_match : matches)
            {
                if (next_match.getStartIndex() < existing_match.getEndIndex() && existing_match.getStartIndex() < next_match.getEndIndex())
                {
                    intersects = true;
                    break;
                }
            }
            if (intersects)
            {
                continue;
            }
            generateMatches(password, next_match, non_intersecting_matches, brute_force_matches, new ArrayList<>(matches), lowest_entropy_matches);
            found_next = true;
        }

        if (!found_next)
        {
            final int lowest_matches_length = getLength(lowest_entropy_matches, false);
            final int matches_length = getLength(matches, false);
            // We always look for the most complete match, even if it's not the lowest entropy.
            if (lowest_entropy_matches.isEmpty() || (matches_length >= lowest_matches_length && (calcEntropy(matches, false) / matches_length) < (calcEntropy(lowest_entropy_matches, false) / lowest_matches_length)))
            {
                backfillBruteForce(password, brute_force_matches, matches);
                lowest_entropy_matches.clear();
                lowest_entropy_matches.addAll(matches);
            }
        }
    }

    /**
     * Method to determine if the password should be considered random, and to just use brute force matches.
     * <p>
     * We determine a password to be random if the matches cover less than 50% of the password, or if they cover less than 80%
     * but the max length for a match is no more than 25% of the total length of the password.
     *
     * @param password the password
     * @param matches  the final list of matches
     * @return true if determined to be random
     */
    private static boolean isRandom(final String password, final List<Match> matches)
    {
        int matched_length = 0;
        int max_matched_length = 0;
        for (Match match : matches)
        {
            if (!(match instanceof BruteForceMatch))
            {
                matched_length += match.getLength();
                if (match.getLength() > max_matched_length)
                {
                    max_matched_length = match.getLength();
                }
            }
        }

        if (matched_length < (password.length() * 0.5))
        {
            return true;
        }
        else if (matched_length < (password.length() * 0.8) && password.length() * 0.25 > max_matched_length)
        {
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Helper method to calculate entropy from a list of matches.
     *
     * @param matches the list of matches
     * @return the sum of the entropy in the list passed in
     */
    private static double calcEntropy(final List<Match> matches, final boolean include_brute_force)
    {
        double entropy = 0;
        for (Match match : matches)
        {
            if (include_brute_force || !(match instanceof BruteForceMatch))
            {
                entropy += match.calculateEntropy();
            }
        }
        return entropy;
    }

    /**
     * Helper method to get the length of password matched from a list of matches.
     *
     * @param matches the list of matches
     * @return the length of the tokens matched
     */
    private static int getLength(final List<Match> matches, final boolean include_brute_force)
    {
        int length = 0;
        for (Match match : matches)
        {
            if (include_brute_force || !(match instanceof BruteForceMatch))
            {
                length += match.getLength();
            }
        }
        return length;
    }

    /**
     * Fills in the matches array passed in with {@link BruteForceMatch} in every missing spot.
     * Returns them unsorted.
     *
     * @param password            the password
     * @param brute_force_matches map of index and brute force match to fit that index
     * @param matches             the list of matches to fill in
     */
    private static void backfillBruteForce(final String password, final Map<Integer, Match> brute_force_matches, final List<Match> matches)
    {
        Set<Match> bf_matches = new HashSet<>();
        int index = 0;
        while (index < password.length())
        {
            boolean has_match = false;
            for (Match match : matches)
            {
                if (index >= match.getStartIndex() && index <= match.getEndIndex())
                {
                    has_match = true;
                }
            }
            if (!has_match)
            {
                bf_matches.add(brute_force_matches.get(index));
            }
            index++;
        }
        matches.addAll(bf_matches);
    }

    /**
     * Gets all matches for a given password.
     *
     * @param configuration the configuration file used to estimate entropy.
     * @param password      the password to get matches for.
     * @return a {@code List} of {@code Match} objects for the supplied password.
     */
    private static List<Match> getAllMatches(final Configuration configuration, final String password)
    {
        List<Match> matches = new ArrayList<>();

        for (PasswordMatcher passwordMatcher : configuration.getPasswordMatchers())
        {
            matches.addAll(passwordMatcher.match(configuration, password));
        }
        keepLowestMatches(matches);
        return matches;
    }

    /**
     * Keeps the lowest entropy matches for the specific start / end index
     *
     * @param matches List of matches to remove duplicate higher entropy matches from.
     */
    private static void keepLowestMatches(final List<Match> matches)
    {
        Set<Match> to_remove = new HashSet<>();
        for (Match match : matches)
        {
            for (Match to_compare : matches)
            {
                if (match.getStartIndex() == to_compare.getStartIndex() && match.getEndIndex() == to_compare.getEndIndex())
                {
                    if (match.calculateEntropy() / match.getLength() > to_compare.calculateEntropy() / to_compare.getLength())
                    {
                        to_remove.add(match);
                        break;
                    }
                }
            }
        }
        matches.removeAll(to_remove);
    }

    /**
     * Creates a brute force match for a portion of the password.
     *
     * @param password      the password to create brute match for
     * @param configuration the configuration
     * @param index         the index of the password part that needs a {@code BruteForceMatch}
     * @return a {@code Match} object
     */
    private static Match createBruteForceMatch(final String password, final Configuration configuration, final int index)
    {
        return new BruteForceMatch(password.charAt(index), configuration, index);
    }

    /**
     * Gets the entropy from the number of guesses passed in.
     *
     * @param guesses a {@code BigDecimal} representing the number of guesses.
     * @return entropy {@code Double} that is calculated based on the guesses.
     */
    public static Double getEntropyFromGuesses(final BigDecimal guesses)
    {
        Double guesses_tmp = guesses.doubleValue();
        guesses_tmp = guesses_tmp.isInfinite() ? Double.MAX_VALUE : guesses_tmp;
        return Math.log(guesses_tmp) / Math.log(2);
    }

    /**
     * Gets the number of guesses from the entropy passed in.
     *
     * @param entropy a {@code Double} representing the number of guesses.
     * @return guesses {@code BigDecimal} that is calculated based on the entropy.
     */
    public static BigDecimal getGuessesFromEntropy(final Double entropy)
    {
        final Double guesses_tmp = Math.pow(2, entropy);
        return new BigDecimal(guesses_tmp.isInfinite() ? Double.MAX_VALUE : guesses_tmp).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Console application which will run with default configurations.
     *
     * @param args arguments which are ignored!
     */
    public static void main(String... args)
    {
        Configuration configuration = new ConfigurationBuilder().createConfiguration();
        Nbvcxz nbvcxz = new Nbvcxz(configuration);
        ResourceBundle resourceBundle = ResourceBundle.getBundle("main", nbvcxz.getConfiguration().getLocale());
        Scanner scanner = new Scanner(System.in);
        System.out.println(resourceBundle.getString("main.howToQuit"));

        String password;

        while (true)
        {
            System.out.println(resourceBundle.getString("main.startPrompt"));
            password = scanner.nextLine();
            if ("\\quit".equals(password))
            {
                break;
            }
            printEstimationInfo(nbvcxz, password);
        }
        System.out.println(resourceBundle.getString("main.quitPrompt") + " ");

    }

    private static void printEstimationInfo(final Nbvcxz nbvcxz, final String password)
    {
        ResourceBundle resourceBundle = ResourceBundle.getBundle("main", nbvcxz.getConfiguration().getLocale());

        long start = System.currentTimeMillis();
        Result result = nbvcxz.estimate(password);
        long end = System.currentTimeMillis();

        System.out.println("----------------------------------------------------------");
        System.out.println(resourceBundle.getString("main.timeToCalculate") + " " + (end - start) + " ms");
        System.out.println(resourceBundle.getString("main.password") + " " + password);
        System.out.println(resourceBundle.getString("main.entropy") + " " + result.getEntropy());
        Feedback feedback = FeedbackUtil.getFeedback(result);
        if (feedback.getWarning() != null)
        {
            System.out.println(resourceBundle.getString("main.feedback.warning") + " " + feedback.getWarning());
        }
        for (String suggestion : feedback.getSuggestion())
        {
            System.out.println(resourceBundle.getString("main.feedback.suggestion") + " " + suggestion);
        }
        Map<String, Long> sortedMap =
                result.getConfiguration().getGuessTypes().entrySet().stream()
                        .sorted(Map.Entry.comparingByValue())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (e1, e2) -> e1, LinkedHashMap::new));
        for (Map.Entry<String, Long> guessType : sortedMap.entrySet())
        {
            System.out.println(resourceBundle.getString("main.timeToCrack") + " " + guessType.getKey() + ": " + TimeEstimate.getTimeToCrackFormatted(result, guessType.getKey()));
        }
        for (Match match : result.getMatches())
        {
            System.out.println("-----------------------------------");
            System.out.println(match.getDetails());
        }
        System.out.println("----------------------------------------------------------");
    }

    /**
     * Gets the current configuration.
     *
     * @return returns {@code Configuration}
     */
    public Configuration getConfiguration()
    {
        return configuration;
    }

    /**
     * Sets the configuration.
     *
     * @param configuration a {@code Configuration} to be used in all estimates.
     */
    public void setConfiguration(Configuration configuration)
    {
        this.configuration = configuration;
    }

    /**
     * Guess the entropy of a password with the configuration provided.
     *
     * @param password The password you would like to attempt to estimate on.
     * @return Result object that contains info about the password.
     */
    public Result estimate(final String password)
    {
        return guessEntropy(this.configuration, password);
    }

    /**
     * Sorts matches by starting index, and length
     */
    private static class StartIndexComparator implements Comparator<Match>
    {
        public int compare(Match match_1, Match match_2)
        {
            if (match_1.getStartIndex() < match_2.getStartIndex())
            {
                return -1;
            }
            else if (match_1.getStartIndex() > match_2.getStartIndex())
            {
                return 1;
            }
            else if (match_1.getStartIndex() == match_2.getStartIndex())
            {
                if (match_1.getToken().length() < match_2.getToken().length())
                {
                    return -1;
                }
                else
                {
                    return 1;
                }
            }
            return 0;
        }
    }
}
